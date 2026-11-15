#!/usr/bin/env bash
# One pass over the phone for LABEL (baseline = Play 0.1.1 on ExecuTorch 1.5.1, candidate = 1.6.0).
set -uo pipefail
LABEL=${1:?label}
HERE=$(cd "$(dirname "$0")" && pwd); OUT="$HERE/$LABEL"; mkdir -p "$OUT"
REPO=$REPO_ROOT; SER=EIHAIRFYORFADMCE
CPU=lfm2.5-1.2b-instruct-8da4w-gptq-32k; GPU=qwen3-1.7b-vulkan-8da4w-gptq-4k; CPU2=lfm2.5-2.6b-8da4w-gptq-32k
B=http://127.0.0.1:8080/v1
UV="uv run -q --with openai --with anthropic --with httpx python"
thermal() { echo "[$(date +%T)] $1 thermal=$(adb -s $SER shell dumpsys thermalservice | awk -F': ' '/Thermal Status/{print $2; exit}') battery=$(adb -s $SER shell dumpsys battery | awk '/temperature/{print $2/10 "C"}')" | tee -a "$OUT/log.txt"; }
load() { curl -fsS -m 600 -X POST -H "Authorization: Bearer $K" "$B/execuserve/models/$1/load" >/dev/null && echo "loaded $1" | tee -a "$OUT/log.txt" || echo "LOAD FAILED $1" | tee -a "$OUT/log.txt"; }
"$REPO/tools/execuserve" -s $SER --model $CPU > "$OUT/start.txt" 2>&1 || { cat "$OUT/start.txt"; exit 1; }
K=$(awk '/API key/{print $3}' "$OUT/start.txt")
adb -s $SER shell dumpsys package org.experimentalmachines.execuserve | grep -m2 -E "versionName|versionCode" | tee -a "$OUT/log.txt"
curl -fsS -H "Authorization: Bearer $K" "$B/models" > "$OUT/models.json"
for M in $CPU $GPU; do
  load $M; thermal "$M start"
  $UV "$HERE/greedy.py" $B "$K" $M "$OUT/$M-greedy.jsonl" > "$OUT/$M-greedy.txt" 2>&1
  $UV "$HERE/accents.py" $B "$K" $M >> "$OUT/accents.txt" 2>&1
  $UV "$REPO/tools/compat/bench.py" $B "$K" $M > "$OUT/$M-bench.txt" 2>&1
  $UV "$REPO/tools/compat/openai_sdk_check.py" $B "$K" $M > "$OUT/$M-openai.txt" 2>&1
  $UV "$REPO/tools/compat/anthropic_sdk_check.py" ${B%/v1} "$K" $M > "$OUT/$M-anthropic.txt" 2>&1
  thermal "$M end"
done
load $CPU
$UV "$REPO/tools/compat/edge_cases.py" $B "$K" $CPU $GPU > "$OUT/edge.txt" 2>&1
load $CPU2; thermal "$CPU2 start"
$UV "$HERE/greedy.py" $B "$K" $CPU2 "$OUT/$CPU2-greedy.jsonl" > "$OUT/$CPU2-greedy.txt" 2>&1
$UV "$REPO/tools/compat/bench.py" $B "$K" $CPU2 > "$OUT/$CPU2-bench.txt" 2>&1
thermal "$CPU2 end"
curl -fsS -H "Authorization: Bearer $K" "$B/execuserve/runs?format=csv" > "$OUT/runs.csv"
curl -fsS -H "Authorization: Bearer $K" "$B/execuserve/status" > "$OUT/status.json"
adb -s $SER logcat -d -b crash > "$OUT/crash-log.txt" 2>&1
for f in "$OUT"/*-openai.txt "$OUT"/*-anthropic.txt "$OUT"/edge.txt; do echo "== $(basename $f): $(tail -1 $f)"; done | tee -a "$OUT/log.txt"
cat "$OUT/accents.txt"; echo "crash lines: $(wc -l < "$OUT/crash-log.txt")"
echo DONE
