# batch21 P1-11: minimal webhook receiver for swap-alarm outbound events (verification only).
# Usage: python _c26_mock_receiver.py <port> <events_file> <secret>
# Each POST /hook: verify hex(HMAC-SHA256(secret, raw body)) against X-Swap-Sign, then append
# one JSON line {ts, event, sign, sign_ok, body} to the events file (UTF-8, no BOM). Returns 200.
import datetime
import hashlib
import hmac
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1])
EVENTS = sys.argv[2]
SECRET = sys.argv[3].encode("utf-8")


def strip_bom(path):
    """Self-heal: external tools (e.g. PowerShell Set-Content -Encoding UTF8) may leave a UTF-8 BOM,
    which breaks standard JSON parsers. Rewrite the events file without BOM when detected."""
    if not os.path.exists(path):
        return False
    with open(path, "rb") as f:
        data = f.read()
    if data.startswith(b"\xef\xbb\xbf"):
        with open(path, "wb") as f:
            f.write(data[3:])
        return True
    return False


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        body_text = raw.decode("utf-8", "replace")
        sign = self.headers.get("X-Swap-Sign", "")
        expected = hmac.new(SECRET, raw, hashlib.sha256).hexdigest()
        rec = {
            "ts": datetime.datetime.now().isoformat(timespec="seconds"),
            "event": self.headers.get("X-Swap-Event", ""),
            "sign": sign,
            "sign_ok": (sign == expected),
            "body": body_text,
        }
        with open(EVENTS, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    if strip_bom(EVENTS):
        print("bom stripped:", EVENTS)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
