#!/usr/bin/env python3
"""P1-12 third-party contract client (stdlib only, non-Java).

Proves the device protocol (S0.3) is implementable from the spec alone:
HMAC-SHA256 canonical strings are hand-written here, independent from
swap-contract / swap-sim Java code.

Cases:
  1) heartbeat positive  -> 200 (platform accepts an external device)
  2) heartbeat negative  -> 401 (tampered signature rejected)
  3) event positive      -> 200 (SOC_REPORT accepted, signature verified)
  4) event negative      -> 401 (tampered signature rejected)

Note: the event positive registers a NEW boot generation for the target
cabinet in the replay guard. Restart swap-sim afterwards (it generates a
fresh bootId) or use a dedicated cabinet, otherwise sim events for that
cabinet may be rejected as cross-generation replay.

Usage:
  python _py_contract_client.py --base http://127.0.0.1:8400/api --cabinet SWAP-C-010 --battery BAT-0109

Secret: env SWAP_DEV_SECRET, else <repo>/.local/dev-secret.txt
Output: _py_contract_out.txt next to this script (PASS/FAIL evidence).
"""
import argparse
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "_py_contract_out.txt"
FAILS = []


def log(msg: str) -> None:
    line = time.strftime("%H:%M:%S ") + msg
    print(line)
    with OUT.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def check(name: str, ok: bool) -> None:
    if ok:
        log("PASS " + name)
    else:
        FAILS.append(name)
        log("FAIL " + name)


def sign(secret: str, canonical: str) -> str:
    return hmac.new(secret.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()


def canonical_heartbeat(cabinet: str, status: int) -> str:
    return cabinet + "|" + str(status)


def canonical_event(cabinet: str, event_type: str, cell_no, battery_no: str, boot_id: str, event_seq: int) -> str:
    return "|".join([
        cabinet, event_type,
        "" if cell_no is None else str(cell_no),
        battery_no or "", boot_id, str(event_seq),
    ])


def post(base: str, path: str, cabinet: str, signature: str, body: dict):
    req = urllib.request.Request(
        base + path,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Device-No": cabinet, "X-Device-Sign": signature},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8400/api")
    ap.add_argument("--cabinet", default="SWAP-C-010")
    ap.add_argument("--battery", default="BAT-0109")
    ap.add_argument("--soc", type=int, default=10)
    args = ap.parse_args()

    repo = HERE.parents[2]  # scripts/verify/batch15 -> repo root
    secret = os.environ.get("SWAP_DEV_SECRET", "").strip()
    if not secret:
        secret_file = repo / ".local" / "dev-secret.txt"
        if not secret_file.exists():
            log("FAIL secret not found (env SWAP_DEV_SECRET or .local/dev-secret.txt)")
            return 1
        secret = secret_file.read_text(encoding="utf-8").strip()
    OUT.write_text("== P1-12 third-party contract client (python, stdlib) ==\n", encoding="utf-8")
    log("cabinet=%s battery=%s base=%s" % (args.cabinet, args.battery, args.base))

    # 1) heartbeat positive
    sig = sign(secret, canonical_heartbeat(args.cabinet, 1))
    status, body = post(args.base, "/device/heartbeat", args.cabinet, sig, {"cabinetNo": args.cabinet, "status": 1})
    check("heartbeat accepted (200, code=0)", status == 200 and '"code":0' in body.replace(" ", ""))
    log("heartbeat resp status=%s body=%s" % (status, body[:120]))

    # 2) heartbeat negative (tampered signature)
    bad = sig[:-1] + ("0" if sig[-1] != "0" else "1")
    status, body = post(args.base, "/device/heartbeat", args.cabinet, bad, {"cabinetNo": args.cabinet, "status": 1})
    check("heartbeat tampered signature rejected (401)", status == 401)

    # 3) event positive (SOC_REPORT, fresh generation)
    boot_id = "py-contract-" + uuid.uuid4().hex[:8]
    event_seq = int(time.time() * 1000) % 1000000000
    canonical = canonical_event(args.cabinet, "SOC_REPORT", None, args.battery, boot_id, event_seq)
    sig = sign(secret, canonical)
    status, body = post(args.base, "/device/event", args.cabinet, sig, {
        "cabinetNo": args.cabinet, "eventType": "SOC_REPORT",
        "batteryNo": args.battery, "soc": args.soc, "bootId": boot_id, "eventSeq": event_seq,
    })
    check("event accepted (200, code=0)", status == 200 and '"code":0' in body.replace(" ", ""))
    log("event resp status=%s body=%s bootId=%s eventSeq=%s" % (status, body[:120], boot_id, event_seq))

    # 4) event negative (tampered signature)
    status, body = post(args.base, "/device/event", args.cabinet, sig[:-1] + ("0" if sig[-1] != "0" else "1"), {
        "cabinetNo": args.cabinet, "eventType": "SOC_REPORT",
        "batteryNo": args.battery, "soc": args.soc, "bootId": boot_id, "eventSeq": event_seq,
    })
    check("event tampered signature rejected (401)", status == 401)

    if FAILS:
        log("PY-CONTRACT FAIL checks=%d" % len(FAILS))
        return 1
    log("PY-CONTRACT PASS")
    log("NOTE restart swap-sim to release the test generation on %s" % args.cabinet)
    return 0


if __name__ == "__main__":
    sys.exit(main())
