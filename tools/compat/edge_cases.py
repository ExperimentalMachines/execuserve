"""
Edge cases against a running ExecuServe, reported as observed, not assumed.

    uv run --with httpx python tools/compat/edge_cases.py http://PHONE:8080/v1 KEY MODEL [OTHER_MODEL]

Each probe prints what it sent, what came back and whether that is what a careful server
should do. Nothing here is a benchmark; timings are printed only where the behaviour is the
timing (how long a disconnected client keeps the lane busy, for example).
"""
import json
import socket
import sys
import threading
import time
from urllib.parse import urlparse

import httpx

BASE, KEY = sys.argv[1].rstrip("/"), sys.argv[2]
MODEL = sys.argv[3]
OTHER = sys.argv[4] if len(sys.argv) > 4 else None
AUTH = {"Authorization": f"Bearer {KEY}"}
URL = urlparse(BASE)
client = httpx.Client(timeout=300)
results = []


def status():
    return client.get(BASE + "/execuserve/status", headers=AUTH).json()


def wait_idle(limit=120):
    start = time.time()
    while time.time() - start < limit:
        s = status()
        if s.get("lane") == "idle" and s.get("queued", 0) == 0:
            return time.time() - start
        time.sleep(0.2)
    return None


def chat(messages, **extra):
    body = {"model": MODEL, "messages": messages, "max_tokens": 32, **extra}
    return client.post(BASE + "/chat/completions", headers=AUTH, json=body)


def probe(name):
    def wrap(fn):
        def run():
            try:
                ok, detail = fn()
            except Exception as failure:  # a probe that crashes is a finding too
                ok, detail = False, f"probe raised {type(failure).__name__}: {failure}"
            results.append((name, ok, detail))
            print(("PASS " if ok else "FLAG ") + name + ": " + detail, flush=True)
        run.__name__ = fn.__name__
        return run
    return wrap


def error_code(response):
    try:
        return response.json()["error"].get("code")
    except Exception:
        return None


@probe("prompt longer than the window")
def over_window():
    text = "word " * 40_000
    r = chat([{"role": "user", "content": text}])
    return r.status_code == 400 and error_code(r) == "context_length_exceeded", f"{r.status_code} {error_code(r)}"


@probe("max_tokens far past the window")
def huge_max_tokens():
    r = chat([{"role": "user", "content": "Say hi."}], max_tokens=1_000_000)
    return r.status_code in (200, 400), f"{r.status_code} {r.text[:160]}"


@probe("n > 1")
def many_choices():
    r = chat([{"role": "user", "content": "Say hi."}], n=3)
    body = r.json()
    choices = len(body.get("choices", [])) if r.status_code == 200 else 0
    ignored = r.headers.get("x-execuserve-ignored")
    # One choice is acceptable only if the server says it ignored n; three is also fine.
    fine = r.status_code == 400 or choices == 3 or (choices == 1 and ignored and "n" in ignored)
    return bool(fine), f"{r.status_code} choices={choices} ignored={ignored}"


@probe("unknown model")
def unknown_model():
    r = client.post(BASE + "/chat/completions", headers=AUTH, json={"model": "nope", "messages": [{"role": "user", "content": "x"}]})
    return r.status_code == 404 and error_code(r) == "model_not_found", f"{r.status_code} {error_code(r)}"


@probe("no messages")
def empty_messages():
    r = client.post(BASE + "/chat/completions", headers=AUTH, json={"model": MODEL, "messages": []})
    return r.status_code == 400, f"{r.status_code} {r.text[:120]}"


@probe("body over 4 MiB")
def big_body():
    r = client.post(BASE + "/chat/completions", headers={**AUTH, "Content-Type": "application/json"}, content=b"{" + b" " * (5 << 20) + b"}")
    return r.status_code == 413, f"{r.status_code}"


@probe("malformed JSON")
def bad_json():
    r = client.post(BASE + "/chat/completions", headers={**AUTH, "Content-Type": "application/json"}, content=b'{"model": ')
    return r.status_code == 400, f"{r.status_code} {error_code(r)}"


@probe("wrong auth scheme")
def wrong_scheme():
    r = client.get(BASE + "/models", headers={"Authorization": f"Token {KEY}"})
    return r.status_code == 401, f"{r.status_code}"


@probe("foreign Host header")
def foreign_host():
    r = client.get(BASE + "/models", headers={**AUTH, "Host": "evil.example"})
    return r.status_code == 403, f"{r.status_code}"


@probe("CORS preflight with CORS off")
def preflight():
    r = client.request("OPTIONS", BASE + "/chat/completions", headers={
        "Origin": "https://evil.example", "Access-Control-Request-Method": "POST"})
    allowed = r.headers.get("access-control-allow-origin")
    return allowed is None, f"{r.status_code} allow-origin={allowed}"


@probe("CJK and emoji survive streaming")
def unicode_stream():
    text = ""
    with client.stream("POST", BASE + "/chat/completions", headers=AUTH, json={
        "model": MODEL, "stream": True, "max_tokens": 64, "temperature": 0,
        "messages": [{"role": "user", "content": "Repeat exactly, nothing else: 你好世界 🙂 ñandú"}]}) as r:
        for line in r.iter_lines():
            if line.startswith("data: ") and line != "data: [DONE]":
                chunk = json.loads(line[6:])
                for choice in chunk.get("choices", []):
                    text += choice.get("delta", {}).get("content") or ""
    broken = "�" in text
    return not broken, f"replacement chars={broken} text={text[:60]!r}"


@probe("stop string across tokens")
def stop_string():
    r = chat([{"role": "user", "content": "Count from one to ten in words, separated by spaces."}],
             stop=["five"], max_tokens=64, temperature=0)
    body = r.json()
    content = body["choices"][0]["message"].get("content") or ""
    return "five" not in content and body["choices"][0]["finish_reason"] == "stop", f"{content[:80]!r} finish={body['choices'][0]['finish_reason']}"


@probe("client leaves during a long prefill")
def leave_during_prefill():
    wait_idle()
    long_text = "The quick brown fox jumps over the lazy dog. " * 250
    sock = socket.create_connection((URL.hostname, URL.port or 80))
    body = json.dumps({"model": MODEL, "stream": True, "max_tokens": 64,
                       "messages": [{"role": "user", "content": long_text + " Summarise."}]}).encode()
    head = (f"POST {URL.path}/chat/completions HTTP/1.1\r\nHost: {URL.netloc}\r\nAuthorization: Bearer {KEY}\r\n"
            f"Content-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n").encode()
    sock.sendall(head + body)
    time.sleep(1.0)
    sock.close()
    left = time.time()
    busy = wait_idle()
    last = status().get("recent", [{}])[0]
    return busy is not None and busy < 30, f"lane busy {busy:.1f}s after the client left; last outcome={last.get('outcome')}"


@probe("two models interleaved")
def interleaved_models():
    if not OTHER:
        return True, "skipped: no second model given"
    wait_idle()
    times = {}

    def one(model, tag):
        start = time.time()
        r = client.post(BASE + "/chat/completions", headers=AUTH, json={
            "model": model, "messages": [{"role": "user", "content": "Say hi."}], "max_tokens": 8})
        times[tag] = (r.status_code, round(time.time() - start, 1))

    threads = [threading.Thread(target=one, args=(m, f"{i}:{m[:12]}")) for i, m in enumerate([MODEL, OTHER, MODEL, OTHER])]
    for t in threads:
        t.start()
        time.sleep(0.05)
    for t in threads:
        t.join()
    resident = [m["id"] for m in status().get("resident", [])]
    return all(code == 200 for code, _ in times.values()), f"{times} resident={resident}"


@probe("one client floods the queue")
def flood():
    wait_idle()
    codes = []
    lock = threading.Lock()

    def one():
        r = chat([{"role": "user", "content": "Say hi."}], max_tokens=4)
        with lock:
            codes.append((r.status_code, r.headers.get("retry-after")))

    threads = [threading.Thread(target=one) for _ in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    limited = [c for c in codes if c[0] == 429]
    return bool(limited) and all(ra for _, ra in limited), f"statuses={sorted(c for c, _ in codes)} retry-after={limited[:1]}"


@probe("assistant message last (prefill a reply)")
def assistant_last():
    r = chat([{"role": "user", "content": "Name a colour."}, {"role": "assistant", "content": "The colour is"}], temperature=0)
    content = r.json()["choices"][0]["message"].get("content") if r.status_code == 200 else None
    return r.status_code in (200, 400), f"{r.status_code} continuation={content!r}"


@probe("image content part")
def image_part():
    r = chat([{"role": "user", "content": [{"type": "text", "text": "What is this?"},
                                           {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgo="}}]}])
    return r.status_code == 400, f"{r.status_code} {error_code(r)} {r.text[:120]}"


@probe("response_format json_schema")
def json_schema():
    r = chat([{"role": "user", "content": "Give a colour as JSON."}],
             response_format={"type": "json_schema", "json_schema": {"name": "c", "schema": {"type": "object"}}})
    return r.status_code in (200, 400), f"{r.status_code} ignored={r.headers.get('x-execuserve-ignored')}"


@probe("logprobs requested")
def logprobs():
    r = chat([{"role": "user", "content": "Say hi."}], logprobs=True, top_logprobs=2)
    return r.status_code in (200, 400), f"{r.status_code} ignored={r.headers.get('x-execuserve-ignored')}"


@probe("tool_choice names one function")
def tool_choice_named():
    tools = [{"type": "function", "function": {"name": "get_time", "parameters": {"type": "object", "properties": {}}}},
             {"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}}}]
    r = chat([{"role": "user", "content": "What's the weather in Manila?"}], tools=tools, max_tokens=128,
             tool_choice={"type": "function", "function": {"name": "get_weather"}})
    body = r.json()
    calls = body["choices"][0]["message"].get("tool_calls") if r.status_code == 200 else None
    return r.status_code in (200, 400), f"{r.status_code} calls={[c['function']['name'] for c in calls or []]} ignored={r.headers.get('x-execuserve-ignored')}"


@probe("keep-alive: ten requests on one connection")
def keep_alive():
    with httpx.Client(timeout=60, limits=httpx.Limits(max_connections=1)) as one:
        codes = [one.get(BASE + "/models", headers=AUTH).status_code for _ in range(10)]
    return codes == [200] * 10, f"{codes}"


@probe("unknown previous_response_id")
def previous_response():
    r = client.post(BASE + "/responses", headers=AUTH, json={"model": MODEL, "input": "hi", "previous_response_id": "resp_x"})
    return r.status_code == 400 and error_code(r) == "previous_response_not_found", f"{r.status_code} {error_code(r)}"


@probe("a stored response continues by id and hits the cache")
def chained_response():
    first = client.post(BASE + "/responses", headers=AUTH, json={"model": MODEL, "input": "Name a colour. /no_think", "max_output_tokens": 24}).json()
    second = client.post(BASE + "/responses", headers=AUTH, json={
        "model": MODEL, "input": "Another. /no_think", "previous_response_id": first["id"], "max_output_tokens": 24}).json()
    third = client.post(BASE + "/responses", headers=AUTH, json={
        "model": MODEL, "input": "One more. /no_think", "previous_response_id": second["id"], "max_output_tokens": 24}).json()
    cached = third.get("usage", {}).get("input_tokens_details", {}).get("cached_tokens", 0)
    return third.get("status") in ("completed", "incomplete") and cached > 0, f"third turn cached {cached} of {third.get('usage', {}).get('input_tokens')}"


@probe("apply-template renders without running")
def apply_template():
    before = status().get("totals", {}).get("completed")
    r = client.post(BASE.removesuffix("/v1") + "/apply-template", headers=AUTH, json={"model": MODEL, "messages": [{"role": "user", "content": "Hi"}]})
    after = status().get("totals", {}).get("completed")
    prompt = r.json().get("prompt", "") if r.status_code == 200 else ""
    return r.status_code == 200 and "Hi" in prompt and before == after, f"{r.status_code} {prompt[:60]!r}"


@probe("metrics need a key and name no client")
def metrics():
    root = BASE.removesuffix("/v1")
    anonymous = client.get(root + "/metrics").status_code
    text = client.get(root + "/metrics", headers=AUTH).text
    ok = anonymous == 401 and "execuserve_requests_total" in text and "execuserve_threads" in text
    return ok, f"anonymous {anonymous}; {len(text.splitlines())} lines; threads line: {[l for l in text.splitlines() if l.startswith('execuserve_threads')]}"


@probe("runs are the caller's own and agree with themselves")
def runs():
    body = client.get(BASE + "/execuserve/runs?limit=50", headers=AUTH).json()
    flagged = [r for r in body["data"] if r["discrepancies"]]
    return not flagged, f"{body['total']} runs, {len(flagged)} with discrepancies {[r['discrepancies'] for r in flagged][:3]}"


@probe("prompt progress when asked, and only then")
def progress():
    words = "The quick brown fox jumps over the lazy dog. " * 80
    def lines(ask):
        out = []
        with client.stream("POST", BASE + "/chat/completions", headers=AUTH, json={
            "model": MODEL, "stream": True, "return_progress": ask, "max_tokens": 8,
            "messages": [{"role": "user", "content": words}]}) as r:
            for line in r.iter_lines():
                if line.startswith("data: {"):
                    out.append(json.loads(line[6:]))
        return out
    asked = [c["prompt_progress"] for c in lines(True) if "prompt_progress" in c]
    unasked = [c for c in lines(False) if "prompt_progress" in c]
    return len(asked) > 1 and not unasked, f"{len(asked)} progress chunks, last {asked[-1] if asked else None}; unasked {len(unasked)}"


@probe("system message in the middle")
def system_middle():
    r = chat([{"role": "user", "content": "Hi."}, {"role": "system", "content": "Answer in French."},
              {"role": "user", "content": "Say thank you."}], temperature=0)
    return r.status_code in (200, 400), f"{r.status_code} {(r.json()['choices'][0]['message'].get('content') if r.status_code == 200 else r.text)[:80]!r}"


if __name__ == "__main__":
    for run in [over_window, huge_max_tokens, many_choices, unknown_model, empty_messages, big_body, bad_json,
                wrong_scheme, foreign_host, preflight, unicode_stream, stop_string, leave_during_prefill,
                interleaved_models, flood, assistant_last, image_part, json_schema, logprobs, tool_choice_named,
                keep_alive, previous_response, chained_response, apply_template, metrics, runs, progress, system_middle]:
        run()
    flagged = [name for name, ok, _ in results if not ok]
    print(f"\n{len(results) - len(flagged)}/{len(results)} as expected; flagged: {flagged}")
