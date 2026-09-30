"""
A long-context conversation benchmark: a cold 2k-token prompt, two follow-ups that should
hit the sequence cache, and a short prompt with a long answer. Prints prefill and decode
figures from the server's own timings.

    uv run --with openai python tools/compat/bench.py http://PHONE:8080/v1 KEY MODEL
"""
import sys, time, json
from openai import OpenAI
base, key, model = sys.argv[1], sys.argv[2], sys.argv[3]
c = OpenAI(base_url=base, api_key=key, max_retries=0, timeout=600)
doc = " ".join(f"Item {i}: the harbour master logs vessel {i*7 % 97} arriving at berth {i % 12} with cargo code {i*13 % 1000}." for i in range(1, 90))
system = "You are a port operations assistant. Use the manifest below to answer.\n\n" + doc
def run(messages, max_tokens, label):
    t0 = time.time()
    r = c.chat.completions.create(model=model, messages=messages, max_tokens=max_tokens, temperature=0,
                                  extra_body={"chat_template_kwargs": {"enable_thinking": False}})
    wall = time.time() - t0
    t = r.model_extra["timings"]; u = r.usage
    print(f"{label:28s} prompt={u.prompt_tokens:5d} cached={u.prompt_tokens_details.cached_tokens:5d} "
          f"prefill={t['prompt_ms']:6d}ms ({t['prompt_per_second']:6.1f} tok/s) decode={t['predicted_n']:4d} tok "
          f"@ {t['predicted_per_second']:5.1f} tok/s  wall={wall:5.2f}s  finish={r.choices[0].finish_reason}")
    return r
h = [{"role": "system", "content": system}, {"role": "user", "content": "Which berth did vessel 14 arrive at? One sentence."}]
a = run(h, 64, "turn 1 (cold, long prompt)")
h += [{"role": "assistant", "content": a.choices[0].message.content}, {"role": "user", "content": "And vessel 21? One sentence."}]
b = run(h, 64, "turn 2 (same conversation)")
h += [{"role": "assistant", "content": b.choices[0].message.content}, {"role": "user", "content": "Summarise the first ten items in a paragraph."}]
run(h, 256, "turn 3 (long answer)")
run([{"role": "user", "content": "Write a 200-word story about a lighthouse keeper."}], 256, "fresh short prompt, 256 out")
