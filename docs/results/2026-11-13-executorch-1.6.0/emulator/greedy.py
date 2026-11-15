"""Greedy probes for comparing two runtimes on the same model: same requests, temperature 0,
results as JSON lines (text, finish reason, usage, server timings).

    uv run --with httpx python greedy.py BASE_URL KEY MODEL OUT.jsonl
"""
import json
import sys
import time

import httpx

BASE, KEY, MODEL, OUT = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3], sys.argv[4]
H = {"Authorization": f"Bearer {KEY}"}
c = httpx.Client(timeout=900)
rows = []
EXTRA = {"chat_template_kwargs": {"enable_thinking": False}}


def chat(messages, **kw):
    body = {"model": MODEL, "messages": messages, "temperature": 0, **EXTRA, **kw}
    t0 = time.time()
    r = c.post(f"{BASE}/chat/completions", headers=H, json=body)
    wall = time.time() - t0
    try:
        j = r.json()
    except Exception:
        j = {"raw": r.text[:500]}
    return r.status_code, j, wall


def record(name, status, j, wall, **more):
    row = {"probe": name, "status": status, "wall_s": round(wall, 2)}
    if status == 200 and "choices" in j:
        ch = j["choices"][0]
        u = j.get("usage", {})
        t = j.get("timings", {})
        row.update(
            text=ch["message"].get("content"),
            finish=ch.get("finish_reason"),
            prompt_tokens=u.get("prompt_tokens"),
            cached=(u.get("prompt_tokens_details") or {}).get("cached_tokens"),
            completion_tokens=u.get("completion_tokens"),
            prompt_ms=t.get("prompt_ms"),
            prompt_tps=t.get("prompt_per_second"),
            decode_n=t.get("predicted_n"),
            decode_tps=t.get("predicted_per_second"),
        )
    else:
        row["error"] = j.get("error", j)
    row.update(more)
    rows.append(row)
    print(json.dumps(row, ensure_ascii=False)[:400], flush=True)
    return row


def stream(messages, **kw):
    body = {"model": MODEL, "messages": messages, "temperature": 0, "stream": True, **EXTRA, **kw}
    parts, finish = [], None
    t0 = time.time()
    with c.stream("POST", f"{BASE}/chat/completions", headers=H, json=body) as r:
        for line in r.iter_lines():
            if not line.startswith("data: ") or line == "data: [DONE]":
                continue
            d = json.loads(line[6:])
            for ch in d.get("choices", []):
                parts.append(ch.get("delta", {}).get("content") or "")
                finish = ch.get("finish_reason") or finish
    return "".join(parts), finish, time.time() - t0


capital = [{"role": "user", "content": "What is the capital of Japan? Answer in one sentence."}]
p1 = record("capital", *chat(capital, max_tokens=64))
record("haiku", *chat([{"role": "user", "content": "Write a haiku about the sea."}], max_tokens=64))
record("primes", *chat([{"role": "user", "content": "List the first five prime numbers, comma separated, nothing else."}], max_tokens=32))

turn1 = [{"role": "user", "content": "My name is Ada and I keep bees. Reply with one short sentence."}]
a1 = record("multi_turn_1", *chat(turn1, max_tokens=48))
if a1.get("text") is not None:
    turn2 = turn1 + [{"role": "assistant", "content": a1["text"]}, {"role": "user", "content": "What is my name and what do I keep?"}]
    record("multi_turn_2", *chat(turn2, max_tokens=48))

record("max_tokens_8", *chat([{"role": "user", "content": "Explain photosynthesis in detail."}], max_tokens=8))
record("stop_newline", *chat([{"role": "user", "content": "Name three colours, one per line."}], max_tokens=48, stop=["\n"]))

text, finish, wall = stream(capital, max_tokens=64)
rows.append({"probe": "capital_stream", "text": text, "finish": finish, "wall_s": round(wall, 2),
             "matches_nonstream": text == p1.get("text")})
print(json.dumps(rows[-1], ensure_ascii=False), flush=True)

doc = " ".join(f"Item {i}: the harbour master logs vessel {i*7 % 97} arriving at berth {i % 12} with cargo code {i*13 % 1000}." for i in range(1, 90))
longp = [{"role": "system", "content": "You are a port operations assistant. Use the manifest below to answer.\n\n" + doc},
         {"role": "user", "content": "Which berth did vessel 14 arrive at? One sentence."}]
record("long_prompt", *chat(longp, max_tokens=48))

over = [{"role": "user", "content": "word " * 6000}]
record("overflow", *chat(over, max_tokens=16))

again = record("capital_again", *chat(capital, max_tokens=64))
rows.append({"probe": "reset_identical", "same": again.get("text") == p1.get("text")})
print(json.dumps(rows[-1]), flush=True)

with open(OUT, "w") as f:
    for r in rows:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
