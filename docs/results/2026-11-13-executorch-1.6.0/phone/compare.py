"""Side by side: baseline vs candidate greedy probes, bench timings and suite tallies."""
import json, pathlib, re, sys
D = pathlib.Path(sys.argv[1]); A, B = D / "baseline", D / "candidate"
for g in sorted(A.glob("*-greedy.jsonl")):
    m = g.name[:-len("-greedy.jsonl")]; c = B / g.name
    if not c.exists(): print(m, "no candidate"); continue
    ra = {r["probe"]: r for r in map(json.loads, g.open())}; rb = {r["probe"]: r for r in map(json.loads, c.open())}
    same = [p for p in ra if "text" in ra[p] and ra[p].get("text") == rb.get(p, {}).get("text")]
    diff = [p for p in ra if "text" in ra[p] and p not in same]
    print(f"\n{m}: {len(same)}/{len(same)+len(diff)} probes identical; differ: {diff}")
    for p in diff:
        print(f"  {p}: {ra[p].get('finish')}/{rb.get(p,{}).get('finish')}\n    1.5.1 {ra[p].get('text')!r:.110}\n    1.6.0 {rb.get(p,{}).get('text')!r:.110}")
    for k in ("reset_identical",):
        print(f"  {k}: {ra.get(k,{}).get('same')} / {rb.get(k,{}).get('same')}")
    sa, sb = ra.get("capital_stream", {}), rb.get("capital_stream", {})
    print(f"  stream = non-stream: {sa.get('matches_nonstream')} / {sb.get('matches_nonstream')}")
pat = re.compile(r"^(.{28}) prompt=\s*(\d+) cached=\s*(\d+) prefill=\s*(\d+)ms \(\s*([\d.]+) tok/s\) decode=\s*(\d+) tok @\s*([\d.]+)")
print("\nbench (prefill tok/s, decode tok/s):")
for f in sorted(A.glob("*-bench.txt")):
    la = [pat.match(l) for l in f.read_text().splitlines()]; lb = [pat.match(l) for l in (B / f.name).read_text().splitlines()] if (B / f.name).exists() else []
    la = [x for x in la if x]; lb = [x for x in lb if x]
    print(" ", f.name[:-10])
    for x, y in zip(la, lb + [None] * len(la)):
        yy = f"{y[5]:>7} {y[7]:>6}  cached {y[3]}" if y else "-"
        print(f"    {x[1].strip():28s} 1.5.1 {x[5]:>7} {x[7]:>6}  cached {x[3]:>5} | 1.6.0 {yy}")
print("\nsuites:")
for f in sorted(A.glob("*-openai.txt")) + sorted(A.glob("*-anthropic.txt")) + [A / "edge.txt"]:
    tb = (B / f.name).read_text().splitlines()[-1] if (B / f.name).exists() else "-"
    print(f"  {f.name:55s} {f.read_text().splitlines()[-1]:30s} | {tb}")
for x in (A, B):
    if (x / "accents.txt").exists(): print(x.name, (x / "accents.txt").read_text().strip().replace("\n", " ; "))
