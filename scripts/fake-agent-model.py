#!/usr/bin/env python3
"""A scripted model, for scripts/verify-run.sh. Nothing here talks to a provider.

Usage: fake-agent-model.py SCRIPT.json LOGFILE [EXPECTED_KEY]
SCRIPT.json maps a payment reference to the final answer the model gives for it. The model first asks for
get_payment, then for get_statement_findings, then gives that final answer: it knows which step it is at from
the number of tool_result blocks in the conversation. It prints the port it listens on (loopback). Each request
appends one line to LOGFILE: the max_tokens and whether the bearer matched. The key is never written down. If
EXPECTED_KEY is given and the bearer does not match, the answer is a 401.
"""
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPT = json.load(open(sys.argv[1]))
LOG = sys.argv[2]
EXPECTED = sys.argv[3] if len(sys.argv) > 3 else None
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
REASONING = "FAKE-REASONING-TEXT-MUST-NEVER-BE-KEPT"


def call_tool(tool, argument):
    return json.dumps({"action": "call_tool", "tool": tool, "argument": argument, "outcome": None, "hypothesis": None,
                       "postings": None, "evidence": None, "explanation": None})


def answer(body):
    messages = body["messages"]
    ids = UUID.findall(messages[1]["content"])
    results = sum(1 for m in messages if m["content"].startswith("<tool_result"))
    if results == 0:
        return call_tool("get_payment", ids[0])
    if results == 1:
        return call_tool("get_statement_findings", ids[1])
    return SCRIPT[ids[0]]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        ok = EXPECTED is None or self.headers.get("Authorization", "") == "Bearer " + EXPECTED
        with open(LOG, "a") as log:
            log.write("max_tokens=%s bearer=%s strict=%s\n" % (
                body.get("max_tokens"), "ok" if ok else "wrong",
                str(body.get("response_format", {}).get("json_schema", {}).get("strict")).lower()))
        if not ok:
            self.send(401, {"error": "unauthorised"})
            return
        self.send(200, {"id": "fake-1", "object": "chat.completion", "choices": [{
            "index": 0, "finish_reason": "stop",
            "message": {"role": "assistant", "content": answer(body), "reasoning": REASONING,
                        "reasoning_content": REASONING}}],
            "usage": {"prompt_tokens": 1500, "completion_tokens": 800}})

    def send(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
print(server.server_address[1], flush=True)
server.serve_forever()
