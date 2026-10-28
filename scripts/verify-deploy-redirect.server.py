"""Fake hosts for scripts/verify-deploy-redirect.test.sh (run with python3 -I).

usage: verify-deploy-redirect.server.py <behaviour> <port-file> [<destination>]

Listens on 127.0.0.1 on a free port, writes that port to <port-file> and
answers every GET, HEAD and POST according to <behaviour>.

The www host: a redirect to https://<destination> (default swapi.build):

  ok           308, Location https://<destination> + the same path and query
  wrong-status 301, otherwise like ok
  wrong-host   308, Location on www.swapi.build (a redirect loop)
  drop-query   308, Location keeps the path but drops the query

The apex host (2.11.0+), the end of the www follow:

  apex         200 text/html, a <main id="main-content" data-ssr-page="mcp">
               with the claude mcp add line on its own host and enough text;
               on /resource/films/1 (2.12.0+) the <main> of the record, with
               George Lucas; on /docs/mcp.md (2.14.0+) the Markdown copy,
               200 text/markdown with the claude mcp add line
  empty-main   200 text/html, the <main> empty on one line followed by a
               footer of more than 200 characters (the case that fooled a sed
               range); /docs/mcp.md too (no Markdown copy)
"""
import http.server
import os
import sys

BEHAVIOUR = sys.argv[1]
PORT_FILE = sys.argv[2]
DESTINATION = sys.argv[3] if len(sys.argv) > 3 else "swapi.build"

FOOTER = "<footer><p>" + "Star Wars and all associated names are trademarks of Lucasfilm Ltd. " * 4 + "</p></footer>"


class Host(http.server.BaseHTTPRequestHandler):
    def _page(self, main):
        body = ("<!doctype html><html><head><title>MCP Server</title></head><body>\n"
                + main + "\n" + FOOTER + "\n</body></html>\n").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html;charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _answer(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if BEHAVIOUR == "apex":
            host = self.headers.get("Host", "")
            prose = "Use the Star Wars API from your AI agent. " * 5
            if self.path.split("?", 1)[0] == "/docs/mcp.md":
                body = ("> HTML version: https://" + host + "/docs/mcp\n\n# MCP Server\n\n" + prose + "\n\n"
                        "```bash\nclaude mcp add --transport http swapi-build https://" + host + "/mcp\n```\n"
                        ).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/markdown; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)
                return
            if self.path.split("?", 1)[0] == "/resource/films/1":
                self._page('<main id="main-content" tabindex="-1" data-ssr-page="resource-detail"'
                           ' data-ssr-type="films" data-ssr-id="1">\n'
                           "<h1>A New Hope</h1>\n<table><tr><th>Director</th><td>George Lucas</td></tr></table>\n"
                           "<p>" + prose + "</p>\n</main>")
                return
            self._page('<main id="main-content" tabindex="-1" data-ssr-page="mcp">\n'
                       "<h1>MCP Server</h1>\n<p>" + prose + "</p>\n"
                       "<pre>claude mcp add --transport http swapi-build https://" + host + "/mcp</pre>\n"
                       "</main>")
            return
        if BEHAVIOUR == "empty-main":
            self._page('<main id="main-content" tabindex="-1"></main>')
            return
        status, host, target = 308, DESTINATION, self.path
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


server = http.server.HTTPServer(("127.0.0.1", 0), Host)
with open(PORT_FILE + ".tmp", "w") as f:
    f.write(str(server.server_address[1]))
# Rename so the test never reads a half-written port.
os.replace(PORT_FILE + ".tmp", PORT_FILE)
server.serve_forever()
