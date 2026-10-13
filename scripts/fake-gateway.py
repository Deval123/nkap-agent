#!/usr/bin/env python3
"""A fake gateway for scripts/verify-run.sh: it serves the files under DIR/gateway, for GET only.

Usage: fake-gateway.py DIR LOGFILE EXPECTED_KEY
Prints the port it listens on (loopback). Each request appends one line to LOGFILE: the method, the path and
whether the bearer matched. The key is never written down. Any method but GET is a 405.
"""
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.join(sys.argv[1], "gateway")
LOG = sys.argv[2]
EXPECTED = sys.argv[3]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body=b"{}"):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def handle_any(self):
        ok = self.headers.get("Authorization", "") == "Bearer " + EXPECTED
        with open(LOG, "a") as log:
            log.write("%s %s bearer=%s\n" % (self.command, self.path, "ok" if ok else "wrong"))
        if self.command != "GET":
            self.reply(405)
        elif not ok:
            self.reply(401)
        else:
            path = os.path.normpath(os.path.join(ROOT, self.path.lstrip("/")))
            if path.startswith(ROOT + os.sep) and os.path.isfile(path):
                self.reply(200, open(path, "rb").read())
            else:
                self.reply(404)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = handle_any


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
print(server.server_address[1], flush=True)
server.serve_forever()
