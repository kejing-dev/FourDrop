"""Loudness evidence (B20 / ui_gates loudness) for an app with short feedback sounds only (2026-11-05 rule): renders a
stretch of real play at the DEFAULT volume (the 80 % stop) with the app's per-event gains (SoundPlayer.gainOf), its
+-5 % rate variation, the per-row pitch drop of the falling ticks and its one-instance-per-event rule, keeps the voiced
parts, and prints the gate's metric (20 ms windows above peak -30 dB) plus the peak. Then the D51 burst check: the same
event 10 times 80 ms apart (one instance: a new play stops the previous one), peak spread in dB.
Rendered, not recorded: the Windows emulator host has no audio loopback capture here.

  python -I tools/loudness.py OUT.wav
"""
import math, os, random, sys, wave
import numpy as np

SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
SR = 44100
VOLUME = 0.80  # the default stop (45 / 80 / 100 %, default 80; design said 35 / 65 / 100 default 65, see deviations)
# keep in step with SoundPlayer.gainOf (Platform.kt)
GAIN = {"pick": 0.85, "tick": 0.9, "land": 1.0, "back": 0.9, "full": 0.8, "slide": 0.9, "win": 0.85, "lose": 0.85}
G = 3.3
ROW_Y = [-0.194 + r * 0.076 for r in range(6)]
LIFT_Y = 0.298


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768
        if w.getframerate() != SR:
            x = np.interp(np.arange(0, len(x), w.getframerate() / SR), np.arange(len(x)), x)
        return x


def variants(name):
    return [n for n in (name, name + "_2", name + "_3") if os.path.exists(os.path.join(SND, n + ".wav"))]


def rate(x, r):
    idx = (np.arange(int(len(x) / r)) * r).astype(int)
    return x[np.minimum(idx, len(x) - 1)]


class Mix:
    def __init__(self, seconds):
        self.buf = np.zeros(int(seconds * SR))
        self.playing = {}  # event -> (start, end) of the instance still sounding

    def play(self, t, name, rnd, pitch=1.0):
        x = load(rnd.choice(variants(name)))
        if name in ("pick", "tick", "land", "back", "slide"):
            x = rate(x, (0.95 + rnd.random() * 0.10) * pitch)
        s = int(t * SR)
        prev = self.playing.get(name)
        if prev and prev[1] > s:  # one instance per event: cut the previous one
            self.buf[s:prev[1]] -= prev[2][s - prev[0]:]
        seg = x * GAIN[name] * VOLUME
        e = min(len(self.buf), s + len(seg))
        self.buf[s:e] += seg[:e - s]
        self.playing[name] = (s, e, seg[:e - s])


def drop(mix, t, row, rnd):
    """A disc released at the hover height into [row]: a tick per row passed (3 % lower each), then the landing."""
    fall = lambda d: math.sqrt(2 * d / G)
    k = 0
    for r in range(5, row, -1):
        k += 1
        mix.play(t + fall(LIFT_Y - ROW_Y[r]), "tick", rnd, 1 - 0.03 * k)
    mix.play(t + fall(LIFT_Y - ROW_Y[row]), "land", rnd)
    return t + fall(LIFT_Y - ROW_Y[row]) + 0.06


def main(out):
    rnd = random.Random(5)
    mix = Mix(32)
    t = 0.3
    heights = [0] * 7
    cols = [3, 3, 2, 4, 2, 2, 4, 1, 5, 4, 3, 1, 5, 6]
    for i, c in enumerate(cols):
        mix.play(t, "pick", rnd)                         # lift to the column (human) / the computer's telegraph
        t += 0.22 + (0.30 if i % 2 else 0.6)
        t = drop(mix, t, heights[c], rnd) + 0.5
        heights[c] += 1
        if i == 5:
            mix.play(t, "full", rnd); t += 0.5           # one refused full-column tap
            mix.play(t, "back", rnd); t += 0.6           # a disc put back
    mix.play(t, "win", rnd); t += 1.2
    mix.play(t, "slide", rnd)                            # pull the slide: the pour
    for k in range(len(cols)):
        mix.play(t + 0.25 + k * 0.035, "tick", rnd, 0.9 + rnd.random() * 0.15)
    t += 1.6
    seq = mix.buf[:int(t * SR)]
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(seq, -1, 1) * 32767).astype("<i2").tobytes())
    win = 882
    peak = np.abs(seq).max()
    e = np.array([np.mean(seq[k:k + win] ** 2) for k in range(0, len(seq) - win + 1, win)])
    voiced = e[e >= (peak * 0.0316) ** 2]
    print("rendered %.1f s at the default 80 %% volume: voiced RMS %.1f dBFS (20 ms windows above peak -30 dB), peak %.1f dBFS"
          % (len(seq) / SR, 10 * math.log10(voiced.mean()), 20 * math.log10(peak)))
    for ev in ("tick", "land", "pick", "back"):
        peaks = []
        for burst in range(3):
            m = Mix(2)
            r2 = random.Random(burst)
            for k in range(10):
                m.play(k * 0.08, ev, r2)
            peaks.append(20 * math.log10(np.abs(m.buf).max()))
        singles = [20 * math.log10(np.abs(load(v)).max() * GAIN[ev] * VOLUME) for v in variants(ev)]
        print("burst x10 %-5s peak %.1f dBFS (single variants %s)" % (ev, max(peaks), " / ".join("%.1f" % s for s in singles)))


main(sys.argv[1])
