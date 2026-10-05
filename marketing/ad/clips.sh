#!/bin/sh
# Real footage as frames the ad can show one at a time: the browser sessions recorded by
# browser.cjs (laptop), and the phone's own screen recordings (start: the Play Store
# foreground-service take; download: the catalog, on the release build). Generated, not committed.
set -eu
cd "$(dirname "$0")"
rm -rf frames/clips
for c in chat asleep; do  # 2x screenshots (browser.cjs), kept sharp for the zoom
  mkdir -p frames/clips/$c
  ffmpeg -hide_banner -loglevel error -i assets/clips/$c.mp4 -vf fps=25,scale=1800:-2 -q:v 2 frames/clips/$c/%04d.jpg
done
mkdir -p frames/clips/start frames/clips/download
ffmpeg -hide_banner -loglevel error -i assets/clips/start.mp4 -vf "fps=30,scale=640:-2" -q:v 3 frames/clips/start/%04d.jpg
ffmpeg -hide_banner -loglevel error -i assets/clips/download.mp4 -vf "fps=15,scale=640:-2" -q:v 3 frames/clips/download/%04d.jpg
echo "clips: $(ls frames/clips/chat | wc -l | tr -d ' ') chat, $(ls frames/clips/asleep | wc -l | tr -d ' ') asleep, $(ls frames/clips/start | wc -l | tr -d ' ') start, $(ls frames/clips/download | wc -l | tr -d ' ') download"
