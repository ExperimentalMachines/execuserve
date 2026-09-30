"""
Sends a small request at a fixed interval for a while and records whether and how fast
each was answered: the test for serving with the screen off, on battery, through Doze and
an OEM freezer. Turn the screen off after it starts.

    uv run --with openai python tools/compat/soak.py http://PHONE:8080/v1 KEY qwen3-1.7b --minutes 30 --every 60
"""
import argparse
import csv
import sys
import time

from openai import OpenAI

parser = argparse.ArgumentParser()
parser.add_argument("base")
parser.add_argument("key")
parser.add_argument("model")
parser.add_argument("--minutes", type=float, default=30)
parser.add_argument("--every", type=float, default=60, help="seconds between requests")
parser.add_argument("--out", default="soak.csv")
args = parser.parse_args()

client = OpenAI(base_url=args.base, api_key=args.key, max_retries=0, timeout=120)
end = time.time() + args.minutes * 60
rows = []
with open(args.out, "w", newline="") as f:
    writer = csv.writer(f)
    writer.writerow(["t", "ok", "seconds", "ttft_ms", "decode_tok_s", "error"])
    while time.time() < end:
        started = time.time()
        try:
            r = client.chat.completions.create(
                model=args.model, messages=[{"role": "user", "content": "Reply with the word ok."}],
                max_tokens=16, extra_body={"chat_template_kwargs": {"enable_thinking": False}},
            )
            timings = (r.model_extra or {}).get("timings", {})
            row = [round(started), 1, round(time.time() - started, 2), timings.get("queue_ms", 0) + timings.get("prompt_ms", 0),
                   timings.get("predicted_per_second", 0), ""]
        except Exception as e:  # noqa: BLE001
            row = [round(started), 0, round(time.time() - started, 2), "", "", repr(e)[:120]]
        writer.writerow(row)
        f.flush()
        rows.append(row)
        print(time.strftime("%H:%M:%S"), "ok" if row[1] else "FAIL", row[2], "s", row[5], flush=True)
        time.sleep(max(0.0, args.every - (time.time() - started)))

ok = [r for r in rows if r[1]]
print(f"\n{len(ok)}/{len(rows)} answered; worst {max((r[2] for r in ok), default=0)} s; written to {args.out}")
sys.exit(0 if len(ok) == len(rows) else 1)
