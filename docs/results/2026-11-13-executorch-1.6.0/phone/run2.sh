#!/usr/bin/env bash
# The LFM2.5-2.6B block of run.sh alone, and the server's records, for LABEL.
set -uo pipefail
LABEL=${1:?label}
HERE=$(cd "$(dirname "$0")" && pwd); OUT="$HERE/$LABEL"; mkdir -p "$OUT"
REPO=$REPO_ROOT; SER=EIHAIRFYORFADMCE; CPU2=lfm2.5-2.6b-8da4w-gptq-32k
B=http://127.0.0.1:8080/v1
UV="uv run -q --with openai --with anthropic --with httpx python"
thermal() { echo "[$(date +%T)] $1 thermal=$(adb -s $SER shell dumpsys thermalservice | awk -F': ' '/Thermal Status/{print $2; exit}') battery=$(adb -s $SER shell dumpsys battery | awk '/temperature/{print $2/10 "C"}')" | tee -a "$OUT/log.txt"; }
"$REPO/tools/execuserve" -s $SER --model $CPU2 > "$OUT/start2.txt" 2>&1 || { cat "$OUT/start2.txt"; exit 1; }
K=$(awk '/API key/{print $3}' "$OUT/start2.txt")
thermal "$CPU2 start (rerun)"
$UV "$HERE/greedy.py" $B "$K" $CPU2 "$OUT/$CPU2-greedy.jsonl" > "$OUT/$CPU2-greedy.txt" 2>&1
$UV "$REPO/tools/compat/bench.py" $B "$K" $CPU2 > "$OUT/$CPU2-bench.txt" 2>&1
thermal "$CPU2 end (rerun)"
curl -fsS -H "Authorization: Bearer $K" "$B/execuserve/runs?format=csv" > "$OUT/runs.csv"
curl -fsS -H "Authorization: Bearer $K" "$B/execuserve/status" > "$OUT/status.json"
adb -s $SER logcat -d -b crash > "$OUT/crash-log.txt" 2>&1
tail -1 "$OUT/$CPU2-greedy.txt" | cut -c1-200; cat "$OUT/$CPU2-bench.txt"; echo "crash lines: $(wc -l < "$OUT/crash-log.txt")"
echo DONE
