# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy", "scipy"]
# ///
# The ad's track: 112 BPM warm electro in F major, synthesised from scratch (no samples, nothing
# licensed). Seeded, so every run writes the same file. It follows the story rather than leading
# it: a quiet ember motif under the hook, the groove arriving when the phone starts serving, a
# hush when the screen goes off and a brighter line when the answer still arrives. Scene cuts
# land on bar lines; bar() in ad.html uses the same grid.
#   uv run music.py   -> out/music.wav, out/music.json
import json
import wave
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfilt

HERE = Path(__file__).parent
SR = 48000
BPM = 112
BEAT = 60 / BPM
BAR = 4 * BEAT
BARS = 27
LENGTH = BARS * BAR + 2.6  # the last chord rings out
N = int(LENGTH * SR)
rng = np.random.default_rng(20261006)

# The story, in bars (ad.html cuts on the same ones).
LOGO = 3            # the mark builds: the first hit
PICK = 5            # pick a model
START = 8           # tap Start
SERVING = (9, 2)    # the app turns green: the groove arrives
LAPTOP = 11         # open it from the laptop
ANSWER = (13, 0)    # the laptop's answer starts
SLEEP = 15          # the screen goes off: the hush
WOKE = (17, 0)      # the answer still arrives
CODE = 19           # your apps use it too
NOS = 22            # no cloud inference, no account, no subscription
END = 24            # the end card


def t_of(bar, beat=0.0):
    return (bar * 4 + beat) * BEAT


def empty():
    return np.zeros(N)


def place(buf, start, sig, gain=1.0):
    i = int(round(start * SR))
    if i >= N or i < 0:
        return
    j = min(N, i + len(sig))
    buf[i:j] += sig[: j - i] * gain


def env(n, a=0.002, d=0.2, curve=4.0):
    t = np.arange(n) / SR
    return np.clip(t / max(a, 1e-6), 0, 1) * np.exp(-curve * np.clip(t - a, 0, None) / max(d, 1e-6))


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def filt(sig, kind, f, order=2):
    w = np.clip(np.array(f, dtype=float) / (SR / 2), 1e-4, 0.999)
    return sosfilt(butter(order, w, btype=kind, output='sos'), sig)


def saw(freq, n, phase=0.0):
    return 2 * ((freq * np.arange(n) / SR + phase) % 1.0) - 1


# --- instruments ---------------------------------------------------------------------

def kick(soft=False):
    n = int(0.4 * SR)
    t = np.arange(n) / SR
    f = 48 + (90 if soft else 120) * np.exp(-t * 34)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, 0.002, 0.32, 4.5)
    click = filt(rng.standard_normal(n), 'band', (1500, 5000)) * env(n, 0.0005, 0.004, 6) * (0.08 if soft else 0.18)
    return np.tanh((body + click) * 1.4)


def clap():
    n = int(0.28 * SR)
    noise = filt(rng.standard_normal(n), 'band', (1200, 5500))
    e = np.zeros(n)
    for k, off in enumerate((0, 0.01, 0.021)):
        i = int(off * SR)
        e[i:] += env(n - i, 0.0005, 0.01 if k < 2 else 0.12, 5)
    return noise * e * 0.45


def shaker(accent=1.0):
    n = int(0.07 * SR)
    return filt(rng.standard_normal(n), 'band', (5000, 12000)) * env(n, 0.012, 0.035, 4) * 0.22 * accent


def rim():
    n = int(0.05 * SR)
    t = np.arange(n) / SR
    return (np.sin(2 * np.pi * 1700 * t) * 0.6 + filt(rng.standard_normal(n), 'high', 3000) * 0.4) * env(n, 0.0003, 0.012, 6) * 0.3


def fm(midi, dur, ratio=2.0, index=1.4, decay=7.0):
    """A soft FM pluck: the ember motif."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    mod = np.sin(2 * np.pi * hz(midi) * ratio * t) * index * np.exp(-t * decay)
    return np.sin(2 * np.pi * hz(midi) * t + mod) * env(n, 0.002, dur * 0.7, 4)


def bell(midi, dur=1.2):
    n = int(dur * SR)
    t = np.arange(n) / SR
    mod = np.sin(2 * np.pi * hz(midi) * 3.5 * t) * 1.8 * np.exp(-t * 5)
    return np.sin(2 * np.pi * hz(midi) * t + mod) * env(n, 0.001, dur * 0.6, 4) * 0.25


def pad(notes, dur, cutoff=1600, attack=0.5):
    n = int(dur * SR)
    out = np.zeros(n)
    for m in notes:
        for d in (-0.07, 0.0, 0.07):
            out += saw(hz(m) * 2 ** (d / 12), n, rng.random())
    out = filt(out / (len(notes) * 3), 'low', cutoff)
    return out * env(n, attack, dur, 0.9)


def bass(midi, dur):
    n = int(dur * SR)
    t = np.arange(n) / SR
    tone = np.sin(2 * np.pi * hz(midi) * t) * 0.85 + filt(saw(hz(midi), n), 'low', 500) * 0.35
    return tone * env(n, 0.004, dur * 0.85, 2.6)


def riser(dur):
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    out = np.zeros(n)
    for s in range(24):
        a, b = s * n // 24, (s + 1) * n // 24
        lo = 300 + 5000 * (s / 24) ** 2
        out[a:b] = filt(noise[a:b], 'band', (lo, min(lo * 2.2, 18000)))
    return out * (t / dur) ** 2 * 0.35


def swell(dur=0.6):
    n = int(dur * SR)
    t = np.arange(n) / SR
    return filt(rng.standard_normal(n), 'band', (700, 6000)) * np.sin(np.pi * t / dur) ** 2 * 0.16


def thump(size=1.0):
    """A warm hit: a sub drop with a soft air layer, no crash."""
    n = int(1.4 * SR)
    t = np.arange(n) / SR
    sub = np.sin(2 * np.pi * np.cumsum(44 + 50 * np.exp(-t * 8)) / SR) * env(n, 0.002, 1.0, 3)
    air = filt(rng.standard_normal(n), 'band', (2000, 9000)) * env(n, 0.002, 0.5, 4) * 0.12
    return np.tanh((sub + air) * 1.2) * size


# --- arrangement ----------------------------------------------------------------------
# F - C - Dm - Bb, a chord a bar: warm, open, a little hopeful.
CHORDS = [[65, 69, 72], [60, 64, 67], [62, 65, 69], [58, 62, 65]]
ROOTS = [41, 36, 38, 34]
# The ember motif: four notes from the chord, an octave up.
MOTIF = [(0, 0), (0.75, 2), (1.5, 1), (2.5, 2)]

drums, low, keys, lead, fx = empty(), empty(), empty(), empty(), empty()
K, KS, CL = kick(), kick(soft=True), clap()


def groove(b, full=True, from_beat=0):
    start, root, chord = t_of(b), ROOTS[b % 4], CHORDS[b % 4]
    for k in range(from_beat, 4):
        place(drums, start + k * BEAT, K, 0.95)
        for s in range(4):
            place(drums, start + k * BEAT + s * BEAT / 4, shaker(1.0 if s == 2 else 0.55), 1.0)
        if full and k in (1, 3):
            place(drums, start + k * BEAT, CL, 0.8)
    if full:
        place(drums, start + 3.75 * BEAT, rim(), 0.8)
    # Bass on the offbeats: root and fifth, an octave jump once a bar.
    for e in range(from_beat * 2, 8):
        note = root + (7 if e in (3, 7) else 0) + (12 if e == 5 else 0)
        place(low, start + e * BEAT / 2 + (BEAT / 4 if e % 2 == 0 else 0), bass(note, BEAT / 2), 0.8)
    place(keys, start, pad(chord, BAR, cutoff=2200 if full else 1500, attack=0.05), 0.38)


def motif(b, octave=12, gain=0.32):
    start, chord = t_of(b), CHORDS[b % 4]
    for at, k in MOTIF:
        place(lead, start + at * BEAT, fm(chord[k] + octave, 0.9), gain)


for b in range(BARS):
    start, chord, root = t_of(b), CHORDS[b % 4], ROOTS[b % 4]
    if b < LOGO:
        # The hook: a pad, the ember motif, a heartbeat of soft kicks from bar 1, a riser into the logo.
        place(keys, start, pad(chord, BAR, cutoff=1100, attack=0.6), 0.45)
        motif(b, gain=0.28)
        if b >= 1:
            place(drums, start, KS, 0.55)
            place(drums, start + 2 * BEAT, KS, 0.45)
        if b == 2:
            place(fx, start + BEAT, riser(BAR - BEAT * 1.5), 0.9)
    elif b < START:
        # The logo and the catalog: the groove without its clap, the motif answering.
        groove(b, full=False)
        if b in (LOGO, PICK):
            motif(b, gain=0.26)
    elif b < SLEEP:
        if b == START:
            # Tap Start: a build, then the groove lands with Serving.
            place(keys, start, pad(chord, BAR, cutoff=1400, attack=0.1), 0.4)
            place(drums, start, K, 0.8)
            for s in range(8):
                place(drums, start + s * BEAT / 2, rim(), 0.25 + 0.06 * s)
            place(fx, start + BEAT, riser(BEAT * 3), 0.6)
        elif b == SERVING[0]:
            # The build runs on to the moment the app turns green; the groove starts there.
            for s in range(4):
                place(drums, start + s * BEAT / 2, rim(), 0.7 + 0.06 * s)
            place(drums, start + BEAT, K, 0.8)
            groove(b, full=True, from_beat=SERVING[1])
        else:
            groove(b, full=True)
            if b >= ANSWER[0]:
                motif(b, octave=24, gain=0.24)
    elif b < CODE:
        # The screen goes off: drums fall away to a muffled heartbeat until the answer arrives.
        woke = b >= WOKE[0]
        place(keys, start, pad(chord, BAR, cutoff=2000 if woke else 900, attack=0.3), 0.5)
        place(drums, start, KS, 0.6)
        place(drums, start + 2 * BEAT, KS, 0.45)
        place(low, start, bass(root, BAR * 0.9), 0.55)
        if woke:
            motif(b, octave=24, gain=0.3)
            for s in range(8):
                place(drums, start + s * BEAT / 2, shaker(0.8), 1.0)
        if b == CODE - 1:
            place(fx, start + 2 * BEAT, riser(2 * BEAT), 0.7)
    elif b < BARS - 1:
        groove(b, full=True)
        if b < NOS or b >= END:
            motif(b, octave=12 if b < NOS else 24, gain=0.22)
    # A swell into each cut.
    if b in (PICK, START, LAPTOP, CODE, NOS, END):
        place(fx, start - 0.55, swell(), 1.0)

# The moments.
place(fx, t_of(LOGO), thump(1.0), 0.9)
place(fx, t_of(*SERVING), thump(0.6), 0.7)
place(fx, t_of(*SERVING), bell(84), 0.8)
place(fx, t_of(*ANSWER), bell(81), 0.7)
place(fx, t_of(SLEEP), thump(0.4), 0.5)
place(fx, t_of(*WOKE), bell(84, 1.6), 0.9)
place(fx, t_of(*WOKE) + BEAT / 2, bell(88, 1.2), 0.5)
for i, at in enumerate((0, 1, 2)):  # the three lines, a beat apart; 'Just your phone' on the next bar
    place(fx, t_of(NOS, at), thump(0.55), 0.6)
    place(fx, t_of(NOS, at), bell(77 + i * 4, 0.9), 0.6)
place(fx, t_of(NOS + 1), bell(84, 1.4), 0.8)
place(fx, t_of(END), thump(0.9), 0.85)
# The final chord rings out after the last bar.
place(keys, t_of(BARS - 1), pad(CHORDS[0] + [77], BAR + 2.6, cutoff=2400, attack=0.02), 0.6)
place(lead, t_of(BARS - 1), fm(CHORDS[0][2] + 12, 2.4, decay=3), 0.35)
place(fx, t_of(BARS - 1), thump(0.6), 0.6)

# The code scene's real tokens, each a soft tick at the moment it arrived (record.py). ad.html
# starts replaying them at the same moment: CODE_STREAM there.
CODE_STREAM = t_of(CODE, 2) + 1.6
stream = HERE / 'assets/stream.json'
if stream.exists():
    for k, tok in enumerate(json.loads(stream.read_text())['tokens']):
        place(fx, CODE_STREAM + tok['t'], fm(91 + (k % 2) * 5, 0.08, index=0.8, decay=40), 0.1)

# Sidechain: the kick gently ducks the melodic parts wherever the groove runs.
duck = np.ones(N)
for b in range(BARS):
    if b < LOGO or b == START or SLEEP <= b < CODE:
        continue
    for k in range(4):
        i, n = int(t_of(b, k) * SR), int(BEAT * SR)
        curve = 1 - 0.5 * np.exp(-np.arange(n) / SR * 12)
        duck[i:i + n] = np.minimum(duck[i:i + n], curve[: len(duck[i:i + n])])

# A dotted-eighth echo on the motif, so the ember line has space around it.
d = int(BEAT * 0.75 * SR)
echo = np.zeros(N)
echo[d:] = lead[:-d] * 0.32
time = np.arange(N) / SR
mix = drums * 0.85 + (low * 0.85 + keys * 0.7 + lead * 0.75) * duck + fx
mix[(time >= t_of(LOGO) - BEAT / 2) & (time < t_of(LOGO))] *= 0.1
mix = filt(mix, 'high', 28)
left = np.tanh((mix + echo * 0.6) * 1.15)
right = np.tanh((mix + np.roll(echo, d // 2) * 0.6) * 1.15)
fade = np.ones(N)
tail = int(1.8 * SR)
fade[-tail:] = np.linspace(1, 0, tail) ** 2
st = np.stack([left * fade, right * fade], axis=1)
st /= np.max(np.abs(st)) / 10 ** (-1 / 20)

OUT = HERE / 'out'
OUT.mkdir(exist_ok=True)
with wave.open(str(OUT / 'music.wav'), 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((st * 32767).astype('<i2').tobytes())
json.dump({'bpm': BPM, 'beat': BEAT, 'bar': BAR, 'bars': BARS, 'length': LENGTH}, open(OUT / 'music.json', 'w'))
print(f'music: {LENGTH:.2f} s, {BARS} bars at {BPM} BPM')
