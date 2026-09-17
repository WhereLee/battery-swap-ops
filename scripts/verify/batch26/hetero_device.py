#!/usr/bin/env python3
"""Heterogeneous device endpoint (P2-13) -- non-Java, stdlib only, independent implementation.

Why this file exists: swap-sim is Java and shares code/contract module with the platform, so a
sceptic can call the loop "self-produced, self-consumed". This process implements the SAME wire
protocol from scratch (HMAC canonical strings, uplink envelope, downlink command verification,
event sequencing) with nothing but the Python standard library, and completes a full swap loop
against the platform: heartbeat -> receive OPEN_CELL -> report DOOR_OPENED -> report
BATTERY_OUT/BATTERY_IN -> order completed.

Wire protocol (document/plans/S0.3-device-protocol-v1.md):
  up   POST {platform}/api/device/heartbeat
       headers: X-Device-No, X-Device-Sign ; body {cabinetNo,status,bootId,eventSeq}
       sign over canonical "cabinetNo|status"
  up   POST {platform}/api/device/event
       headers: X-Device-No, X-Device-Sign ; body {cabinetNo,eventType,cellNo,batteryNo,soc,
       commandSeq,bootId,eventSeq} ; sign over "cabinetNo|eventType|cellNo|batteryNo|bootId|eventSeq"
  down POST {device}/cmd        header X-Device-Sign ; body {cabinetNo,action,commandSeq,cellNo}
       sign over "cabinetNo|cellNo|commandSeq" ; reply {"code":0} accepted / {"code":1} rejected
  down POST {device}/cmd/query  header X-Device-Sign ; body {cabinetNo}
       sign over "cabinetNo|QUERY|0" ; reply {"code":0,"data":snapshot}

Device semantics implemented here (deliberately device-realistic, not platform-convenient):
  * bootId per process; eventSeq monotonic and persisted (survives restart like flash storage)
  * command idempotency: a commandSeq is accepted once, duplicates are ignored with code=0
  * events are triggered by PHYSICAL actions, not guessed: opening a door only reports
    DOOR_OPENED; taking/inserting a battery is reported when the local sensor seam says so
    (/local/remove, /local/insert simulate the rider action + RFID read)
  * ordered at-least-once uplink: single sender thread, bounded queue, head-of-line hold on
    failure (never skip or reorder -- a larger seq arriving first would be rejected by the
    platform sequence guard, which equals real data loss), backoff 1..5s then 5s cadence
  * graceful shutdown on SIGINT/SIGTERM: stop accepting, drain within budget, persist state

Usage:
  set HETERO_DEVICE_SECRET=<32 hex, same value the platform stores for this cabinet>
  python hetero_device.py --cabinet SWAP-C-005 --listen 8600 --cabinet-index 5
Platform side must point its downlink base at this process:
  --swap.device.sim-base-url=http://127.0.0.1:8600
"""

import argparse
import hashlib
import hmac
import json
import os
import queue
import signal
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import request as urlrequest
from urllib.error import HTTPError, URLError

DEVICE_HEADER = "X-Device-No"
SIGN_HEADER = "X-Device-Sign"
CABINET_STATUS_ONLINE = 1
DOOR_OPEN_DELAY_S = 0.3
SENDER_BACKOFF_MAX = 5
SHUTDOWN_DRAIN_BUDGET_S = 5.0
SESSION_FRESH_S = 300.0        # open-session freshness window (contract 5: 5 minutes)


# ---------------------------------------------------------------- protocol primitives

def sign(secret: str, canonical: str) -> str:
    return hmac.new(secret.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()


def nz(value) -> str:
    return "" if value is None else str(value)


def canonical_event(cabinet_no, event_type, cell_no, battery_no, boot_id, event_seq) -> str:
    return "|".join([nz(cabinet_no), nz(event_type), nz(cell_no), nz(battery_no),
                     nz(boot_id), nz(event_seq)])


def canonical_heartbeat(cabinet_no, status) -> str:
    return "|".join([nz(cabinet_no), nz(status)])


def canonical_command(cabinet_no, cell_no, command_seq) -> str:
    return "|".join([nz(cabinet_no), nz(cell_no), nz(command_seq)])


def canonical_query(cabinet_no) -> str:
    return "|".join([nz(cabinet_no), "QUERY", "0"])


def log(message: str) -> None:
    sys.stdout.write("%s %s\n" % (time.strftime("%H:%M:%S"), message))
    sys.stdout.flush()


# ---------------------------------------------------------------- device model

class Device:
    """One cabinet, implemented independently of the Java simulator."""

    def __init__(self, args, secret: str):
        self.cabinet_no = args.cabinet
        self.secret = secret
        self.platform = args.platform.rstrip("/")
        self.state_path = args.state
        self.cells_per_cabinet = args.cells
        self.cabinet_index = args.cabinet_index
        self.boot_id = uuid.uuid4().hex
        self.lock = threading.Lock()
        self.event_seq = 0
        self.cells = {}          # cellNo -> batteryNo | None
        self.seen_commands = set()
        # Contract 5 session semantics (mirrors the Java simulator): an OPEN_CELL opens a
        # per-cell session that BATTERY_OUT/BATTERY_IN report back; a take keeps it, the
        # return ends it, and it expires after SESSION_FRESH_S.
        self.session_seq = {}    # cellNo -> commandSeq of the opening OPEN_CELL
        self.session_at = {}     # cellNo -> monotonic timestamp of that session
        self.outbox = queue.Queue(maxsize=args.buffer)
        self.running = True
        self.counters = {"sent": 0, "retried": 0, "cmd_accepted": 0, "cmd_rejected": 0,
                         "cmd_duplicate": 0, "bad_sign": 0}
        self._load_or_seed()

    # --- state (persisted: a real device keeps seq + cell contents across reboots) ---

    def _fixture_battery_no(self, cell_no: int) -> str:
        # Fixture convention shared with the platform seeder: (cabinetIndex-1)*cells + cellNo.
        # A real device reads this from the battery RFID instead.
        return "BAT-%04d" % ((self.cabinet_index - 1) * self.cells_per_cabinet + cell_no)

    def _load_or_seed(self) -> None:
        if os.path.exists(self.state_path):
            try:
                with open(self.state_path, "r", encoding="utf-8") as handle:
                    state = json.load(handle)
                self.event_seq = int(state.get("eventSeq", 0))
                self.cells = {int(k): v for k, v in state.get("cells", {}).items()}
                log("state restored from %s eventSeq=%d cells=%d"
                    % (self.state_path, self.event_seq, len(self.cells)))
                return
            except Exception as exc:  # corrupt state -> reseed, but say so loudly
                log("state restore failed (%s) -> reseeding" % exc)
        for cell_no in range(1, self.cells_per_cabinet + 1):
            self.cells[cell_no] = self._fixture_battery_no(cell_no) if cell_no <= 6 else None
        log("state seeded cells=%d full=%d (fixture batteries)" % (self.cells_per_cabinet, 6))
        self._persist()

    def _persist(self) -> None:
        try:
            with open(self.state_path, "w", encoding="utf-8") as handle:
                json.dump({"eventSeq": self.event_seq, "cells": self.cells}, handle)
        except Exception as exc:
            log("state persist failed: %s" % exc)

    # --- uplink: single sender thread, head-of-line hold, backoff ---

    def _next_seq(self) -> int:
        with self.lock:
            self.event_seq += 1
            return self.event_seq

    def enqueue_event(self, event_type: str, cell_no=None, battery_no=None, soc=None,
                      command_seq=None) -> int:
        seq = self._next_seq()
        body = {"cabinetNo": self.cabinet_no, "eventType": event_type, "cellNo": cell_no,
                "batteryNo": battery_no, "soc": soc, "commandSeq": command_seq,
                "bootId": self.boot_id, "eventSeq": seq}
        canonical = canonical_event(self.cabinet_no, event_type, cell_no, battery_no,
                                    self.boot_id, seq)
        item = ("event", "/api/device/event", body, canonical, event_type, seq)
        while self.running:
            try:
                self.outbox.put(item, timeout=1.0)
                log("event queued %s seq=%d cellNo=%s batteryNo=%s commandSeq=%s"
                    % (event_type, seq, nz(cell_no), nz(battery_no), nz(command_seq)))
                return seq
            except queue.Full:
                log("uplink buffer FULL (%d) -- holding producer (never drop, never reorder)"
                    % self.outbox.maxsize)
        return seq

    def sender_loop(self) -> None:
        attempt = 0
        while self.running or not self.outbox.empty():
            try:
                item = self.outbox.get(timeout=0.5)
            except queue.Empty:
                continue
            while self.running:
                if self._post(item):
                    attempt = 0
                    break
                attempt += 1
                self.counters["retried"] += 1
                delay = min(attempt, SENDER_BACKOFF_MAX)
                if attempt == 1:
                    log("uplink failing -- head-of-line hold on %s seq=%s (backoff %ds)"
                        % (item[4], item[5], delay))
                time.sleep(delay)
            if not self.running:
                # shutdown with a queued item: best effort once, then stop (platform QUERY_STATE
                # / reconciliation is the documented safety net)
                self._post(item)

    def _post(self, item) -> bool:
        kind, path, body, canonical, label, seq = item
        url = self.platform + path
        data = json.dumps(body).encode("utf-8")
        request = urlrequest.Request(url, data=data, method="POST")
        request.add_header("Content-Type", "application/json")
        request.add_header(DEVICE_HEADER, self.cabinet_no)
        request.add_header(SIGN_HEADER, sign(self.secret, canonical))
        try:
            with urlrequest.urlopen(request, timeout=5) as response:
                payload = json.loads(response.read().decode("utf-8"))
                if payload.get("code") != 0:
                    log("uplink rejected by platform %s seq=%s code=%s msg=%s"
                        % (label, seq, payload.get("code"), payload.get("msg")))
                    return False
                self.counters["sent"] += 1
                log("uplink ok %s seq=%s http=%d" % (label, seq, response.status))
                if kind == "event":
                    self._persist()
                return True
        except (HTTPError, URLError, OSError, ValueError) as exc:
            log("uplink error %s seq=%s: %s" % (label, seq, exc))
            return False

    def heartbeat_loop(self, interval: float) -> None:
        while self.running:
            body = {"cabinetNo": self.cabinet_no, "status": CABINET_STATUS_ONLINE,
                    "bootId": self.boot_id, "eventSeq": self.event_seq}
            item = ("heartbeat", "/api/device/heartbeat", body,
                    canonical_heartbeat(self.cabinet_no, CABINET_STATUS_ONLINE), "HEARTBEAT", "-")
            if not self._post(item):
                log("heartbeat failed (will retry next tick; liveness is HTTP by design)")
            for _ in range(int(interval * 10)):
                if not self.running:
                    return
                time.sleep(0.1)

    # --- downlink: platform -> device ---

    def handle_cmd(self, payload: dict, signature: str):
        cabinet_no = payload.get("cabinetNo")
        # Contract 2.3: OPEN_CELL omits action (absent == OPEN_CELL); only explicit
        # actions (e.g. SET_CHARGE_POLICY) carry one.
        action = payload.get("action") or "OPEN_CELL"
        raw_seq = payload.get("commandSeq")
        if cabinet_no != self.cabinet_no:
            self.counters["cmd_rejected"] += 1
            return 200, {"code": 1, "msg": "not my cabinet: %s" % cabinet_no}
        if raw_seq is None:
            return 400, {"code": 1, "msg": "missing commandSeq"}
        try:
            command_seq = int(raw_seq)
        except (TypeError, ValueError):
            return 400, {"code": 1, "msg": "bad commandSeq"}
        cell_no = payload.get("cellNo")

        expected = sign(self.secret, canonical_command(cabinet_no, cell_no, command_seq))
        if not hmac.compare_digest(expected, signature or ""):
            self.counters["bad_sign"] += 1
            log("downlink signature INVALID action=%s seq=%s -> 401" % (action, command_seq))
            return 401, {"code": 1, "msg": "invalid platform signature"}

        if action != "OPEN_CELL":
            self.counters["cmd_rejected"] += 1
            return 200, {"code": 1, "msg": "unsupported action: %s" % action}
        if cell_no is None or int(cell_no) not in self.cells:
            self.counters["cmd_rejected"] += 1
            return 200, {"code": 1, "msg": "unknown cellNo: %s" % cell_no}
        cell_no = int(cell_no)

        with self.lock:
            if command_seq in self.seen_commands:
                self.counters["cmd_duplicate"] += 1
                log("duplicate command ignored seq=%d (idempotent)" % command_seq)
                return 200, {"code": 0, "msg": "duplicate ignored seq=%d" % command_seq}
            self.seen_commands.add(command_seq)
            self.counters["cmd_accepted"] += 1
            # contract 5: remember the open session so the physical action can report it back
            self.session_seq[cell_no] = command_seq
            self.session_at[cell_no] = time.monotonic()

        threading.Thread(target=self._open_door, args=(cell_no, command_seq),
                         daemon=True).start()
        log("OPEN_CELL accepted cellNo=%d seq=%d" % (cell_no, command_seq))
        return 200, {"code": 0, "msg": "accepted"}

    def _open_door(self, cell_no: int, command_seq: int) -> None:
        time.sleep(DOOR_OPEN_DELAY_S)   # door motor
        self.enqueue_event("DOOR_OPENED", cell_no=cell_no, command_seq=command_seq)
        log("door opened cellNo=%d (waiting for physical battery action)" % cell_no)

    def handle_query(self, payload: dict, signature: str):
        cabinet_no = payload.get("cabinetNo")
        expected = sign(self.secret, canonical_query(cabinet_no))
        if not hmac.compare_digest(expected, signature or ""):
            self.counters["bad_sign"] += 1
            return 401, {"code": 1, "msg": "invalid platform signature"}
        return 200, {"code": 0, "data": self.snapshot()}

    def snapshot(self) -> dict:
        with self.lock:
            return {"cabinetNo": self.cabinet_no, "bootId": self.boot_id,
                    "eventSeq": self.event_seq, "status": CABINET_STATUS_ONLINE,
                    "cells": {str(k): v for k, v in sorted(self.cells.items())},
                    "counters": dict(self.counters), "pendingUplink": self.outbox.qsize()}

    def _fresh_session_seq(self, cell_no: int):
        """Command seq of the open session for this cell (None if absent/expired).
        Mirrors the Java simulator: the take keeps the session, the return ends it."""
        with self.lock:
            at = self.session_at.get(cell_no)
            if at is None or time.monotonic() - at > SESSION_FRESH_S:
                self.session_seq.pop(cell_no, None)
                self.session_at.pop(cell_no, None)
                return None
            return self.session_seq.get(cell_no)

    # --- local sensor seams (simulate the rider's physical action + RFID read) ---

    def local_remove(self, cell_no: int):
        with self.lock:
            battery_no = self.cells.get(cell_no)
            if battery_no is None:
                return {"code": 1, "msg": "cell %d already empty" % cell_no}
            self.cells[cell_no] = None
        command_seq = self._fresh_session_seq(cell_no)   # session survives the take (ends on return)
        log("sensor: battery %s removed from cell %d" % (battery_no, cell_no))
        self.enqueue_event("BATTERY_OUT", cell_no=cell_no, battery_no=battery_no,
                           command_seq=command_seq)
        return {"code": 0, "msg": "removed", "batteryNo": battery_no}

    def local_insert(self, cell_no: int, battery_no: str, soc: int):
        with self.lock:
            if self.cells.get(cell_no):
                return {"code": 1, "msg": "cell %d occupied by %s" % (cell_no, self.cells[cell_no])}
            self.cells[cell_no] = battery_no
        command_seq = self._fresh_session_seq(cell_no)
        with self.lock:                                  # session ends with the return
            self.session_seq.pop(cell_no, None)
            self.session_at.pop(cell_no, None)
        log("sensor: battery %s inserted into cell %d soc=%d" % (battery_no, cell_no, soc))
        self.enqueue_event("BATTERY_IN", cell_no=cell_no, battery_no=battery_no, soc=soc,
                           command_seq=command_seq)
        return {"code": 0, "msg": "inserted", "batteryNo": battery_no}

    def shutdown(self) -> None:
        self.running = False
        deadline = time.time() + SHUTDOWN_DRAIN_BUDGET_S
        while not self.outbox.empty() and time.time() < deadline:
            time.sleep(0.1)
        self._persist()
        log("shutdown: persisted eventSeq=%d pending=%d counters=%s"
            % (self.event_seq, self.outbox.qsize(), self.counters))


# ---------------------------------------------------------------- HTTP plumbing

class Handler(BaseHTTPRequestHandler):
    device: "Device" = None  # injected by main()

    def _json_body(self) -> dict:
        raw = self._read_raw_body()
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def _read_raw_body(self) -> bytes:
        """Read the request body whether it arrives with Content-Length (plain) or
        Transfer-Encoding: chunked (some clients stream JSON without a fixed length).
        A minimal embedded HTTP stack often only handles the former; this device
        tolerates both so it stays a strict-but-fair conformance peer."""
        transfer_encoding = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in transfer_encoding:
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    while True:                      # consume trailers up to the blank line
                        trailer = self.rfile.readline()
                        if trailer in (b"\r\n", b"\n", b""):
                            break
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)                   # CRLF after each chunk
            return b"".join(chunks)
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return b""
        return self.rfile.read(length)

    def _reply(self, status: int, payload: dict) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):  # noqa: N802 (http.server API)
        path = self.path.split("?")[0]
        if path == "/health":
            self._reply(200, {"status": "UP"})
        elif path == "/local/status":
            self._reply(200, {"code": 0, "data": self.device.snapshot()})
        else:
            self._reply(404, {"code": 1, "msg": "no such path"})

    def do_POST(self):  # noqa: N802 (http.server API)
        path = self.path.split("?")[0]
        try:
            payload = self._json_body()
        except Exception as exc:
            self._reply(400, {"code": 1, "msg": "bad json: %s" % exc})
            return
        signature = self.headers.get(SIGN_HEADER)
        if path == "/cmd":
            status, body = self.device.handle_cmd(payload, signature)
            self._reply(status, body)
        elif path == "/cmd/query":
            status, body = self.device.handle_query(payload, signature)
            self._reply(status, body)
        elif path == "/local/remove":
            self._reply(200, self.device.local_remove(int(payload["cellNo"])))
        elif path == "/local/insert":
            self._reply(200, self.device.local_insert(int(payload["cellNo"]),
                                                      payload["batteryNo"],
                                                      int(payload.get("soc", 100))))
        else:
            self._reply(404, {"code": 1, "msg": "no such path"})

    def log_message(self, *args) -> None:   # silence default access log; we log ourselves
        pass


# ---------------------------------------------------------------- main

def main() -> int:
    parser = argparse.ArgumentParser(description="Heterogeneous (non-Java) swap cabinet device")
    parser.add_argument("--cabinet", required=True, help="cabinetNo registered on the platform")
    parser.add_argument("--listen", type=int, default=8600, help="downlink HTTP port")
    parser.add_argument("--platform", default="http://127.0.0.1:8400", help="platform base (no /api)")
    parser.add_argument("--cells", type=int, default=12, help="cells per cabinet")
    parser.add_argument("--cabinet-index", type=int, required=True,
                        help="1-based cabinet index (fixture battery numbering)")
    parser.add_argument("--heartbeat", type=float, default=10.0, help="heartbeat interval seconds")
    parser.add_argument("--state", default=None, help="state file (default .local/hetero-<cabinet>.json)")
    parser.add_argument("--buffer", type=int, default=200, help="uplink buffer size")
    args = parser.parse_args()

    secret = os.environ.get("HETERO_DEVICE_SECRET") or os.environ.get("SWAP_DEV_SECRET")
    if not secret or len(secret.strip()) < 32:
        sys.stderr.write("FATAL: set HETERO_DEVICE_SECRET (32 hex) -- zero plaintext in repo\n")
        return 2
    if args.state is None:
        args.state = os.path.join(".local", "hetero-%s.json" % args.cabinet)
    os.makedirs(os.path.dirname(args.state) or ".", exist_ok=True)

    device = Device(args, secret.strip())
    Handler.device = device
    server = ThreadingHTTPServer(("127.0.0.1", args.listen), Handler)

    threading.Thread(target=device.sender_loop, name="hetero-sender", daemon=True).start()
    threading.Thread(target=device.heartbeat_loop, args=(args.heartbeat,),
                     name="hetero-heartbeat", daemon=True).start()
    threading.Thread(target=server.serve_forever, name="hetero-http", daemon=True).start()

    def stop(signum, _frame):
        log("signal %s received -- shutting down" % signum)
        device.shutdown()
        server.shutdown()

    signal.signal(signal.SIGINT, stop)
    try:
        signal.signal(signal.SIGTERM, stop)
    except (AttributeError, ValueError):
        pass

    log("heterogeneous device up: cabinet=%s downlink=127.0.0.1:%d platform=%s bootId=%s state=%s"
        % (device.cabinet_no, args.listen, device.platform, device.boot_id, device.state_path))
    while device.running:
        time.sleep(0.5)
    server.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
