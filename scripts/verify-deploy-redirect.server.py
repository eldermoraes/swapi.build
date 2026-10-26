"""Fake www host for scripts/verify-deploy-redirect.test.sh (run with python3 -I).

usage: verify-deploy-redirect.server.py <behaviour> <port-file>

Listens on 127.0.0.1 on a free port, writes that port to <port-file> and
answers every GET, HEAD and POST with a redirect to swapi.build whose shape
depends on <behaviour>:

  ok           308, Location https://swapi.build + the same path and query
  wrong-status 301, otherwise like ok
  wrong-host   308, Location on www.swapi.build (a redirect loop)
  drop-query   308, Location keeps the path but drops the query
"""
import http.server
import os
import sys

BEHAVIOUR = sys.argv[1]
PORT_FILE = sys.argv[2]


class Redirect(http.server.BaseHTTPRequestHandler):
    def _answer(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        status, host, target = 308, "swapi.build", self.path
        if BEHAVIOUR == "wrong-status":
            status = 301
        elif BEHAVIOUR == "wrong-host":
            host = "www.swapi.build"
        elif BEHAVIOUR == "drop-query":
            target = self.path.split("?", 1)[0]
        self.send_response(status)
        self.send_header("Location", "https://" + host + target)
        self.send_header("Content-Length", "0")
        self.end_headers()

    do_GET = do_HEAD = do_POST = _answer

    def log_message(self, *args):
        pass


server = http.server.HTTPServer(("127.0.0.1", 0), Redirect)
with open(PORT_FILE + ".tmp", "w") as f:
    f.write(str(server.server_address[1]))
# Rename so the test never reads a half-written port.
os.replace(PORT_FILE + ".tmp", PORT_FILE)
server.serve_forever()
