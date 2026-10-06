#!/bin/sh
# Real footage as frames the ad can show one at a time: the phones' own screen recordings
# (start: Start on a Snapdragon 8 Elite Gen 5, portrait; npu: the in-app Chat on its NPU) and a browser session recorded by browser.cjs (asleep: another
# device asking the POCO's MediaTek NPU while the phone is locked). Generated, not committed.
set -eu
cd "$(dirname "$0")"
rm -rf frames/clips
mkdir -p frames/clips/asleep frames/clips/start frames/clips/npu
# 2x screenshots (browser.cjs), kept sharp for the zoom.
ffmpeg -hide_banner -loglevel error -i assets/clips/npu-poco-asleep.mp4 -vf fps=25,scale=1800:-2 -q:v 2 frames/clips/asleep/%04d.jpg
# screenrecord writes a frame only when the screen changes, so the take ends on Serving: held.
ffmpeg -hide_banner -loglevel error -i assets/clips/start.mp4 -vf "fps=30,tpad=stop_mode=clone:stop_duration=6,scale=640:-2" -q:v 3 frames/clips/start/%04d.jpg
# The QDC phone records landscape at 3200 x 1440.
ffmpeg -hide_banner -loglevel error -i assets/clips/npu-sm8850-chat.mp4 -vf "fps=30,scale=1600:-2" -q:v 2 frames/clips/npu/%04d.jpg
echo "clips: $(ls frames/clips/asleep | wc -l | tr -d ' ') asleep, $(ls frames/clips/start | wc -l | tr -d ' ') start, $(ls frames/clips/npu | wc -l | tr -d ' ') npu"
