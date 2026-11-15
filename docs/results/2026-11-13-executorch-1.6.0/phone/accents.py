"""Asks the model to repeat accented Latin text; prints prompt tokens and the reply."""
import json, sys, httpx
BASE, KEY, MODEL = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]
r = httpx.post(f"{BASE}/chat/completions", headers={"Authorization": f"Bearer {KEY}"}, timeout=600, json={
    "model": MODEL, "temperature": 0, "max_tokens": 24, "chat_template_kwargs": {"enable_thinking": False},
    "messages": [{"role": "user", "content": "Repeat exactly, nothing else: ñandú café naïve"}]}).json()
print(MODEL, r["usage"]["prompt_tokens"], json.dumps(r["choices"][0]["message"]["content"].strip(), ensure_ascii=False))
