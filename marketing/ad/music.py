# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy", "scipy"]
# ///
# The ad's track: 112 BPM warm electro in F major, synthesised from scratch (no samples, nothing
# licensed). Seeded, so every run writes the same file. It follows the story rather than leading
# it: the ember motif under the hook falling away as the chip goes cold, a hit for each processor,
# a bell as each bar of the chart finishes, the groove arriving when the phone starts serving, a
# tick while the NPU reads, a hush when the screen goes off and a brighter line when the answer
# still arrives. Scene cuts land on bar lines; bar() in ad.html uses the same grid.
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
BARS = 25
LENGTH = BARS * BAR + 2.6  # the last chord rings out
N = int(LENGTH * SR)
rng = np.random.default_rng(20261006)

# The story, in bars (ad.html cuts on the same ones).
COLD = (1, 2)       # the hook's second line: the chip's die goes cold
LOGO = 3            # the mark builds: the first hit
CHIPS = 5           # CPU, GPU, NPU, NPU: a card a beat from (5, 1)
CHART = 7           # time to the first token
CHART_GO = (7, 1.5) # the bars start growing; each stops after its measured seconds
TTFT = (0.41, 1.0, 2.0)  # NPU, GPU, CPU (docs/results/2026-10-06-npu.md)
START = 9           # tap Start
SERVING = (10, 0)   # the app turns green: the groove arrives
NPU = 11            # the Snapdragon's NPU reads a document
SEND = (12, 0)      # Send; the reply finishes its recorded seconds later
NPU_TOOK = 11.0 - 7.75  # npu-sm8850-chat: sent and done, read from the screen (data.cjs)
NET = 15            # every app can use it
SLEEP = (15, 2)     # the power button: the screen goes off, the hush
WOKE = (18, 0)      # the answer still arrives
NOS = 20            # no cloud, no account, no subscription
END = 22            # the end card


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


def groove(b, full=True, from_beat=0, to_beat=4):
    start, root, chord = t_of(b), ROOTS[b % 4], CHORDS[b % 4]
    for k in range(from_beat, to_beat):
        place(drums, start + k * BEAT, K, 0.95)
        for s in range(4):
            place(drums, start + k * BEAT + s * BEAT / 4, shaker(1.0 if s == 2 else 0.55), 1.0)
        if full and k in (1, 3):
            place(drums, start + k * BEAT, CL, 0.8)
    if full and to_beat == 4:
        place(drums, start + 3.75 * BEAT, rim(), 0.8)
    # Bass on the offbeats: root and fifth, an octave jump once a bar.
    for e in range(from_beat * 2, to_beat * 2):
        note = root + (7 if e in (3, 7) else 0) + (12 if e == 5 else 0)
        place(low, start + e * BEAT / 2 + (BEAT / 4 if e % 2 == 0 else 0), bass(note, BEAT / 2), 0.8)
    place(keys, start + from_beat * BEAT, pad(chord, BEAT * (to_beat - from_beat), cutoff=2200 if full else 1500, attack=0.05), 0.38)


def motif(b, octave=12, gain=0.32, upto=4.0):
    start, chord = t_of(b), CHORDS[b % 4]
    for at, k in MOTIF:
        if at < upto:
            place(lead, start + at * BEAT, fm(chord[k] + octave, 0.9), gain)


def tap(at, gain=0.25):
    """A finger on glass: a short high click."""
    place(fx, at, fm(96, 0.06, index=0.5, decay=60), gain)


for b in range(BARS):
    start, chord, root = t_of(b), CHORDS[b % 4], ROOTS[b % 4]
    if b < LOGO:
        # The hook: a pad and the ember motif, which falls away when the chip goes cold; a
        # heartbeat of soft kicks from bar 1, a riser into the logo.
        place(keys, start, pad(chord, BAR, cutoff=1100 if b < 2 else 700, attack=0.6), 0.45)
        if b == 0:
            motif(b, gain=0.28)
        if b == COLD[0]:
            motif(b, gain=0.28, upto=COLD[1])
        if b >= 1:
            place(drums, start, KS, 0.55)
            place(drums, start + 2 * BEAT, KS, 0.45)
        if b == 2:
            place(fx, start + BEAT, riser(BAR - BEAT * 1.5), 0.9)
    elif b < START:
        # The logo: the groove without its clap; the processors and the chart: with it.
        groove(b, full=b >= CHIPS)
        if b in (LOGO, CHART):
            motif(b, gain=0.24)
    elif b == START:
        # Tap Start: a build, then the groove lands with Serving.
        place(keys, start, pad(chord, BAR, cutoff=1400, attack=0.1), 0.4)
        place(drums, start, K, 0.8)
        for s in range(8):
            place(drums, start + s * BEAT / 2, rim(), 0.25 + 0.06 * s)
        place(fx, start + BEAT, riser(BEAT * 3), 0.6)
    elif b < NET:
        groove(b, full=True)
        if b == SERVING[0] or b >= SEND[0] + 2:
            motif(b, octave=24, gain=0.22)
    elif b == NET:
        # The groove runs to the power button, then drops away.
        groove(b, full=True, to_beat=SLEEP[1])
        place(keys, t_of(*SLEEP), pad(chord, BEAT * 2, cutoff=900, attack=0.2), 0.45)
        place(drums, t_of(*SLEEP), KS, 0.6)
    elif b < NOS:
        # The screen is off: a muffled heartbeat until the answer arrives, then it brightens.
        woke = b >= WOKE[0]
        place(keys, start, pad(chord, BAR, cutoff=2000 if woke else 900, attack=0.3), 0.5)
        place(drums, start, KS, 0.6)
        place(drums, start + 2 * BEAT, KS, 0.45)
        place(low, start, bass(root, BAR * 0.9), 0.55)
        if woke:
            motif(b, octave=24, gain=0.3)
            for s in range(8):
                place(drums, start + s * BEAT / 2, shaker(0.8), 1.0)
        if b == NOS - 1:
            place(fx, start + 2 * BEAT, riser(2 * BEAT), 0.7)
    elif b < BARS - 1:
        groove(b, full=True)
        if b >= END:
            motif(b, octave=24, gain=0.22)
    # A swell into each cut.
    if b in (CHIPS, START, NPU, NET, NOS, END):
        place(fx, start - 0.55, swell(), 1.0)

# The moments.
place(lead, t_of(*COLD), fm(CHORDS[1][0] - 12, 1.6, index=2.2, decay=2), 0.3)  # the die goes cold
place(fx, t_of(LOGO), thump(1.0), 0.9)
for i in range(4):  # a card a beat: CPU, GPU, then the two NPUs, brighter
    at = t_of(CHIPS, 1 + i)
    place(fx, at, thump(0.35 if i < 2 else 0.5), 0.55)
    place(fx, at, bell((72, 76, 79, 84)[i], 0.9), 0.5 if i < 2 else 0.75)
for secs, note in zip(TTFT, (84, 77, 72)):  # each chart bar stops on a bell; the NPU first and brightest
    place(fx, t_of(*CHART_GO) + secs, bell(note, 1.2), 0.8 if note == 84 else 0.5)
tap(t_of(*SERVING) - (9.9 - 6.15) / 2)  # ad.html's tap on Start, before the sped-up loading
place(fx, t_of(*SERVING), thump(0.6), 0.7)
place(fx, t_of(*SERVING), bell(84), 0.8)
# Send, the NPU at work (a soft tick on each half beat, as the ember pulses), and the reply done.
tap(t_of(*SEND))
for k in range(int(NPU_TOOK / (BEAT / 2))):
    place(fx, t_of(*SEND) + k * BEAT / 2, rim(), 0.3)
done = t_of(*SEND) + NPU_TOOK
place(fx, done, bell(81, 1.4), 0.85)
place(fx, done + 0.6, bell(84, 0.8), 0.45)  # the app's own figures, one a beat apart
place(fx, done + 0.6 + BEAT, bell(88, 0.8), 0.45)
# The power button, the other device's send, and the answer arriving with the screen off.
tap(t_of(*SLEEP), 0.3)
place(fx, t_of(*SLEEP), thump(0.4), 0.5)
asleep = json.loads((HERE / 'assets/clips/npu-poco-asleep.json').read_text())['marks']
tap(t_of(*WOKE) - (asleep['firstText'] - asleep['sent']), 0.2)
place(fx, t_of(*WOKE), bell(84, 1.6), 0.9)
place(fx, t_of(*WOKE) + BEAT / 2, bell(88, 1.2), 0.5)
for i, at in enumerate((0, 1, 2)):  # the three lines, a beat apart; the last on the next bar
    place(fx, t_of(NOS, at), thump(0.55), 0.6)
    place(fx, t_of(NOS, at), bell(77 + i * 4, 0.9), 0.6)
place(fx, t_of(NOS + 1), bell(84, 1.4), 0.8)
place(fx, t_of(END), thump(0.9), 0.85)
# The final chord rings out after the last bar.
place(keys, t_of(BARS - 1), pad(CHORDS[0] + [77], BAR + 2.6, cutoff=2400, attack=0.02), 0.6)
place(lead, t_of(BARS - 1), fm(CHORDS[0][2] + 12, 2.4, decay=3), 0.35)
place(fx, t_of(BARS - 1), thump(0.6), 0.6)

# Sidechain: the kick gently ducks the melodic parts wherever the groove runs.
duck = np.ones(N)
hush = (t_of(*SLEEP), t_of(*WOKE))
for b in range(BARS):
    if b < LOGO or b == START:
        continue
    for k in range(4):
        if hush[0] <= t_of(b, k) < hush[1]:
            continue
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
