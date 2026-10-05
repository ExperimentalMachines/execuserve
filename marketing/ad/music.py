# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy", "scipy"]
# ///
# The ad's track: 124 BPM techno in D minor, synthesised from scratch (no samples, nothing
# licensed). Seeded, so every run writes the same file. Scene cuts land on bar lines; bar() in
# ad.html uses the same grid. When assets/stream.json (the phone's real tokens, recorded by
# record.py) is there, each token gets a soft blip at the moment it arrived.
#   uv run music.py   -> out/music.wav, out/music.json
import json
import wave
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfilt

HERE = Path(__file__).parent
SR = 48000
BPM = 124
BEAT = 60 / BPM
BAR = 4 * BEAT
BARS = 25
LENGTH = BARS * BAR + 2.4  # the last chord rings out
N = int(LENGTH * SR)
rng = np.random.default_rng(20261005)

# The arrangement, in bars; ad.html cuts on the same ones.
TYPE_AT = (1, 0)      # the URL is retyped from here, one character per 32nd note
DROP = 4              # the mark assembles
STREAM = 9            # the phone's real tokens stream
CACHE_HIT = (13, 0)   # the warm turn's bar collapses
BREAK = (14, 16)      # connect anything: half-time
CHECKS = 18           # what it guarantees, one per two beats
END_HIT = 22          # the end card
LAST = 24             # the last groove bar
URL = 'http://127.0.0.1:8080/v1'


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
    att = np.clip(t / max(a, 1e-6), 0, 1)
    dec = np.exp(-curve * np.clip(t - a, 0, None) / max(d, 1e-6))
    return att * dec


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def filt(sig, kind, f, order=2):
    w = np.array(f, dtype=float) / (SR / 2)
    w = np.clip(w, 1e-4, 0.999)
    return sosfilt(butter(order, w, btype=kind, output='sos'), sig)


def saw(freq, n, phase=0.0):
    t = np.arange(n) / SR
    return 2 * ((freq * t + phase) % 1.0) - 1


def crush(sig, bits=6, hold=3):
    """A little digital grit: fewer levels, held samples."""
    q = np.round(sig * 2 ** (bits - 1)) / 2 ** (bits - 1)
    return np.repeat(q[::hold], hold)[: len(sig)]


# --- instruments ---------------------------------------------------------------------

def kick(punch=1.0):
    n = int(0.42 * SR)
    t = np.arange(n) / SR
    f = 44 + 140 * np.exp(-t * 42)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, 0.001, 0.3, 5)
    click = filt(rng.standard_normal(n), 'high', 2500) * env(n, 0.0004, 0.005, 6) * 0.4
    return np.tanh((body + click) * 1.8 * punch)


def clap():
    n = int(0.32 * SR)
    noise = filt(rng.standard_normal(n), 'band', (1000, 6000))
    e = np.zeros(n)
    for k, off in enumerate((0, 0.009, 0.019)):
        i = int(off * SR)
        e[i:] += env(n - i, 0.0004, 0.01 if k < 2 else 0.16, 5)
    return noise * e * 0.6


def hat(open_=False):
    n = int((0.2 if open_ else 0.045) * SR)
    return filt(rng.standard_normal(n), 'high', 8000) * env(n, 0.0004, 0.14 if open_ else 0.022, 5) * 0.3


def tick():
    n = int(0.025 * SR)
    t = np.arange(n) / SR
    return (np.sin(2 * np.pi * 2900 * t) + 0.4 * filt(rng.standard_normal(n), 'high', 6000)) * env(n, 0.0003, 0.008, 6) * 0.3


def key_click():
    n = int(0.018 * SR)
    return filt(rng.standard_normal(n), 'band', (1800, 7500)) * env(n, 0.0002, 0.005, 6) * 0.32


def blip(midi=88):
    """A token arriving: a short FM ping."""
    n = int(0.07 * SR)
    t = np.arange(n) / SR
    mod = np.sin(2 * np.pi * hz(midi) * 2 * t) * 1.6 * np.exp(-t * 60)
    return np.sin(2 * np.pi * hz(midi) * t + mod) * env(n, 0.0005, 0.03, 5) * 0.16


def bell(midi, dur=0.9):
    n = int(dur * SR)
    t = np.arange(n) / SR
    mod = np.sin(2 * np.pi * hz(midi) * 3.5 * t) * 2.2 * np.exp(-t * 6)
    return np.sin(2 * np.pi * hz(midi) * t + mod) * env(n, 0.001, dur * 0.6, 4) * 0.22


def supersaw(notes, dur, cutoff=3000, detune=0.16, voices=5):
    n = int(dur * SR)
    out = np.zeros(n)
    for m in notes:
        for v in range(voices):
            cents = (v - (voices - 1) / 2) * detune * 100 / ((voices - 1) / 2)
            out += saw(hz(m) * 2 ** (cents / 1200), n, rng.random())
    return filt(out / (len(notes) * voices), 'low', cutoff)


def pluck(midi, dur=0.16, cutoff=4200):
    n = int(dur * SR)
    tone = saw(hz(midi), n) * 0.6 + saw(hz(midi) * 1.005, n, 0.3) * 0.4
    return crush(filt(tone, 'low', cutoff), 7, 2) * env(n, 0.001, dur * 0.5, 5)


def bass(midi, dur):
    n = int(dur * SR)
    t = np.arange(n) / SR
    tone = saw(hz(midi), n) * 0.55 + np.sin(2 * np.pi * hz(midi) * t) * 0.75
    return filt(tone, 'low', 700) * env(n, 0.002, dur * 0.8, 2.5)


def riser(dur):
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    out = np.zeros(n)
    steps = 32
    for s in range(steps):
        a, b = s * n // steps, (s + 1) * n // steps
        lo = 250 + 7000 * (s / steps) ** 2
        out[a:b] = filt(noise[a:b], 'band', (lo, min(lo * 2.3, 20000)))
    tone = np.sin(2 * np.pi * np.cumsum(110 * 2 ** (3 * t / dur)) / SR) * 0.25
    return (out + tone) * (t / dur) ** 2 * 0.55


def downer(dur=0.6):
    """A falling sweep: the cold turn collapsing into the warm one."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = 1400 * 2 ** (-5 * t / dur)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, 0.005, dur, 2) * 0.22


def whoosh(dur=0.45):
    n = int(dur * SR)
    t = np.arange(n) / SR
    return filt(rng.standard_normal(n), 'band', (500, 7000)) * np.sin(np.pi * t / dur) ** 2 * 0.25


def impact(size=1.0):
    n = int(1.8 * SR)
    t = np.arange(n) / SR
    sub = np.sin(2 * np.pi * np.cumsum(36 + 70 * np.exp(-t * 7)) / SR) * env(n, 0.002, 1.3, 3)
    crash = filt(rng.standard_normal(n), 'high', 3000) * env(n, 0.001, 1.0, 4) * 0.3
    return np.tanh((sub * 1.2 + crash) * 1.3) * size


# --- arrangement ----------------------------------------------------------------------
# D minor: Dm - Bb - F - C, a chord a bar.
CHORDS = [[62, 65, 69], [58, 62, 65], [53, 57, 60], [60, 64, 67]]
ROOTS = [38, 34, 41, 36]

drums, low, keys, lead, fx = empty(), empty(), empty(), empty(), empty()
K, CL = kick(), clap()

for b in range(BARS):
    chord, root, start = CHORDS[b % 4], ROOTS[b % 4], t_of(b)
    if b < 2:
        # Under the code: a clock ticking in eighths, a dark filtered pad.
        for e in range(8):
            place(drums, start + e * BEAT / 2, tick(), 1.0 if e % 2 == 0 else 0.55)
        place(keys, start, supersaw(chord, BAR, cutoff=700) * env(int(BAR * SR), 0.6, BAR, 0.6), 0.55)
        place(low, start, bass(root, BAR) * 0.6, 0.6)
    elif b < DROP:
        # The build: kick in, a snare roll that doubles, the filter opening, a riser.
        for k in range(4):
            place(drums, start + k * BEAT, K, 0.6)
        per = 8 if b == 2 else 16
        for s in range(per):
            place(drums, start + s * BAR / per, CL, 0.2 + 0.45 * s / per)
        place(keys, start, supersaw(chord, BAR, cutoff=1100 + 2200 * (b - 2)), 0.5)
        if b == 2:
            place(fx, start, riser(2 * BAR - BEAT / 2), 1.0)
    elif BREAK[0] <= b < BREAK[1]:
        # Connect anything: half time, an open pad, a bell answering on the off-bar.
        place(drums, start, K, 0.8)
        place(drums, start + 2.5 * BEAT, K, 0.55)
        place(drums, start + 2 * BEAT, CL, 0.6)
        for s in range(16):
            place(drums, start + s * BEAT / 4, hat(), 0.32 if s % 2 == 0 else 0.18)
        place(keys, start, supersaw(chord, BAR, cutoff=2400) * env(int(BAR * SR), 0.08, BAR, 1.0), 0.55)
        place(lead, start + BEAT * 1.5, bell(chord[2] + 12), 0.9)
        place(lead, start + BEAT * 3, bell(chord[1] + 12), 0.7)
        if b == BREAK[1] - 1:
            place(fx, start + 2 * BEAT, riser(2 * BEAT), 0.9)
    elif b <= LAST:
        # The groove.
        for k in range(4):
            place(drums, start + k * BEAT, K, 1.0)
            place(drums, start + k * BEAT + BEAT / 2, hat(open_=True), 0.75)
            for s in (0.25, 0.75):
                place(drums, start + k * BEAT + s * BEAT, hat(), 0.45)
            if k in (1, 3):
                place(drums, start + k * BEAT, CL, 0.85)
        # A rolling sixteenth bass, an octave jump on the last of each beat.
        for s in range(16):
            place(low, start + s * BEAT / 4, bass(root + (12 if s % 4 == 3 else 0), BEAT / 4), 0.85)
        # Stabs on the offbeats and a bed under them.
        for k in range(4):
            stab = supersaw([n + 12 for n in chord], BEAT / 2, cutoff=4600) * env(int(BEAT / 2 * SR), 0.002, 0.18, 5)
            place(keys, start + k * BEAT + BEAT / 2, stab, 0.75)
        place(keys, start, supersaw(chord, BAR, cutoff=1700), 0.22)
        # The arp: chord tones in sixteenths, gritty, an octave up.
        tones = [chord[0] + 12, chord[2] + 12, chord[1] + 12, chord[2] + 24]
        for s in range(16):
            place(lead, start + s * BEAT / 4, pluck(tones[s % 4] + (12 if s % 8 == 6 else 0)), 0.3)
    # A whoosh into every cut.
    if b in (6, 9, 12, 14, 16, 18, 20):
        place(fx, start - 0.42, whoosh(), 1.0)

# Typing: one click per 32nd note while the URL is retyped.
for i in range(len(URL)):
    place(fx, t_of(*TYPE_AT) + i * BEAT / 8, key_click(), 1.0 if i % 2 == 0 else 0.7)

# The phone's real tokens, each at the moment it arrived.
stream = HERE / 'assets/stream.json'
if stream.exists():
    rec = json.loads(stream.read_text())
    for k, tok in enumerate(rec['tokens']):
        at = t_of(STREAM, 2) + tok['t']
        if at < t_of(STREAM + 3):
            place(fx, at, blip(86 + (k % 3) * 3), 1.0)

for bar_, size in ((DROP, 1.0), (END_HIT, 0.9)):
    place(fx, t_of(bar_), impact(size), 0.9)
place(fx, t_of(*CACHE_HIT) - 0.55, downer(), 1.0)
place(fx, t_of(*CACHE_HIT), impact(0.5), 0.7)
place(fx, t_of(*CACHE_HIT), bell(86, 1.2), 1.0)
for i in range(4):  # one bell per guarantee, a beat and a quarter apart, as ad.html reveals them
    place(fx, t_of(CHECKS, i * 1.25), bell(81 + i * 2, 0.7), 0.8)
# The final chord rings out after the groove.
place(keys, t_of(LAST + 1), supersaw(CHORDS[0], 2.4, cutoff=2400) * env(int(2.4 * SR), 0.005, 2.2, 2.4), 0.75)
place(fx, t_of(LAST + 1), impact(0.8), 0.8)

# Sidechain: the kick ducks everything melodic from the drop on, outside the break.
duck = np.ones(N)
for b in range(DROP, LAST + 1):
    if BREAK[0] <= b < BREAK[1]:
        continue
    for k in range(4):
        i, n = int(t_of(b, k) * SR), int(BEAT * SR)
        curve = 1 - 0.7 * np.exp(-np.arange(n) / SR * 13)
        duck[i:i + n] = np.minimum(duck[i:i + n], curve[: len(duck[i:i + n])])

mix = drums * 0.85 + (low * 0.8 + keys * 0.62 + lead * 0.55) * duck + fx
# The half beat of silence before the drop.
time = np.arange(N) / SR
mix[(time >= t_of(DROP) - BEAT / 2) & (time < t_of(DROP))] *= 0.06
mix = filt(mix, 'high', 28)
left = np.tanh(mix * 1.25 + lead * 0.09 * duck)
right = np.tanh(mix * 1.25 - lead * 0.09 * duck)
fade = np.ones(N)
tail = int(1.4 * SR)
fade[-tail:] = np.linspace(1, 0, tail) ** 2
stereo = np.stack([left * fade, right * fade], axis=1)
stereo /= np.max(np.abs(stereo)) / 10 ** (-1 / 20)

OUT = HERE / 'out'
OUT.mkdir(exist_ok=True)
with wave.open(str(OUT / 'music.wav'), 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((stereo * 32767).astype('<i2').tobytes())
json.dump({'bpm': BPM, 'beat': BEAT, 'bar': BAR, 'bars': BARS, 'length': LENGTH}, open(OUT / 'music.json', 'w'))
print(f'music: {LENGTH:.2f} s, {BARS} bars at {BPM} BPM' + (' with the recorded tokens' if stream.exists() else ''))
