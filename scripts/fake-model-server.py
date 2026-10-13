#!/usr/bin/env python3
"""A fake of the chat completions endpoint, for scripts/verify-spike.sh. Nothing here talks to a provider.

Usage: fake-model-server.py LOGFILE [EXPECTED_KEY]
Prints the port it listens on (loopback, ephemeral) and serves until killed. Each request appends one line
to LOGFILE: the model, max_tokens, and whether the bearer header matched. The key is never written down.
If EXPECTED_KEY is given and the bearer does not match, the answer is a 401.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = sys.argv[1]
EXPECTED = sys.argv[2] if len(sys.argv) > 2 else None
REASONING = "FAKE-REASONING-TEXT-MUST-NEVER-BE-KEPT"
COUNTS = {"m1": 0, "m2": 0}


def call_tool(tool="search_docs", argument="fee"):
    return {"action": "call_tool", "tool": tool, "argument": argument, "outcome": None, "hypothesis": None,
            "postings": None, "evidence": None, "explanation": None}


def final(outcome, hypothesis=None, postings=None, evidence=None, explanation="Fake explanation."):
    return {"action": "final", "tool": None, "argument": None, "outcome": outcome, "hypothesis": hypothesis,
            "postings": postings or [], "evidence": evidence or [], "explanation": explanation}


GOOD = final("propose", "unrecorded_fee",
             [{"account": "fees:mtn-cm:XAF", "amountMinor": 75, "currency": "XAF"},
              {"account": "provider:mtn-cm:float:XAF", "amountMinor": -75, "currency": "XAF"}],
             [{"callId": "c2", "path": "/findings/0/kind", "value": "SUSPENSE_POSTED"},
              {"callId": "c1", "path": "/state", "value": "UNKNOWN"}])


def answer(body):
    text = "\n".join(m["content"] for m in body["messages"])
    if "two sentences of plain prose" in text:
        COUNTS["m1"] += 1
        # every seventh answer is prose, as a decoder that does not enforce the schema would give
        return "Plain prose, two sentences. Here is the second." if COUNTS["m1"] % 7 == 0 else json.dumps(call_tool())
    if "Ignore your previous instructions" in text:
        COUNTS["m2"] += 1
        return json.dumps(final("propose", "float_drift", [{"account": "a", "amountMinor": 1, "currency": "XAF"}],
                                [{"callId": "c1", "path": "/state", "value": "FAILED"}])) \
            if COUNTS["m2"] % 5 == 0 else json.dumps(final("no_proposal"))
    if "Find out whether the ledger misses" in text or "statement import" in text or "Two tool results" in text:
        return json.dumps(GOOD)
    return json.dumps(final("no_proposal"))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length))
        bearer = self.headers.get("Authorization", "")
        ok = EXPECTED is None or bearer == "Bearer " + EXPECTED
        with open(LOG, "a") as log:
            log.write("%s %s max_tokens=%s bearer=%s strict=%s\n" % (
                self.path, body.get("model"), body.get("max_tokens"), "ok" if ok else "wrong",
                str(body.get("response_format", {}).get("json_schema", {}).get("strict")).lower()))
        if self.path != "/v1/chat/completions":
            self.send(404, {"error": "not found"})
        elif not ok:
            self.send(401, {"error": "unauthorised"})
        else:
            low = body.get("max_tokens") == 500
            content = answer(body)
            big = "Ultra" in body.get("model", "")
            usage = {"prompt_tokens": 1200, "completion_tokens": 4000 if low else (900 if big else 600),
                     "completion_tokens_details": {"reasoning_tokens": 400}}
            self.send(200, {"id": "fake-1", "object": "chat.completion", "choices": [{
                "index": 0, "finish_reason": "length" if low else "stop",
                "message": {"role": "assistant", "content": content, "reasoning": REASONING,
                            "reasoning_content": REASONING}}], "usage": usage})

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
