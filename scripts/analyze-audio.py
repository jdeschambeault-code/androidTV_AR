#!/usr/bin/env python3
"""Looks at a recording from the audio test: clicks, sudden level jumps, silence, and how much of the tremble is in the sound itself.
Usage: analyze-audio.py path/to/sound.wav"""
import sys, wave
import numpy as np

w = wave.open(sys.argv[1]); rate = w.getframerate()
x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).reshape(-1, 2).astype(np.float32) / 32768
print("%.1f s, peak %.3f, rms %.3f" % (len(x) / rate, np.abs(x).max(), np.sqrt((x ** 2).mean())))
mono = x.mean(axis=1)

# a click: a sample jump far bigger than the jumps normally around it
d = np.abs(np.diff(x, axis=0)).max(axis=1)
hop = rate // 20
local = np.array([np.median(d[i:i + hop]) for i in range(0, len(d), hop)])
limit = np.repeat(local, hop)[:len(d)] * 12 + 0.01
hits = np.where(d > limit)[0]
groups = []
for h in hits:
    if not groups or h - groups[-1][-1] > rate // 100: groups.append([h])
    else: groups[-1].append(h)
print("clicks: %d" % len(groups))
for g in groups[:25]: print("  %7.2f s  jump %.3f" % (g[0] / rate, d[g[0]]))

# level over time, every 100 ms
win = rate // 10
levels = np.array([np.sqrt((x[i:i + win] ** 2).mean()) for i in range(0, len(x) - win, win)])
db = 20 * np.log10(levels + 1e-5)
print("\nlevel per second (dB):")
for s in range(0, len(db) // 10):
    seg = db[s * 10:(s + 1) * 10]
    print("  %3d s  mean %6.1f  min %6.1f  max %6.1f  swing %5.1f%s" % (s, seg.mean(), seg.min(), seg.max(), seg.max() - seg.min(), "  <<" if seg.max() - seg.min() > 6 else ""))
print("\nsilent stretches (under -60 dB for over 100 ms): %d" % int((db < -60).sum()))

# tremble: what rate the loudness pulses at, in stereo and if the two channels are heard together
def pulses(sig, name):
    seg = rate // 50
    env = np.array([np.sqrt((sig[i:i + seg] ** 2).mean()) for i in range(0, len(sig) - seg, seg)])
    for start in range(0, len(env) - 500, 500):
        e = env[start:start + 500]; e = e - e.mean()
        spec = np.abs(np.fft.rfft(e * np.hanning(len(e))))
        f = np.fft.rfftfreq(len(e), 1 / 50)
        band = (f > 0.5) & (f < 25)
        peak = f[band][spec[band].argmax()]
        depth = 2 * spec[band].max() / (np.hanning(len(e)).sum()) / (env[start:start + 500].mean() + 1e-6)
        print("  %-14s %3d-%3d s  strongest pulse %5.2f Hz, depth %3.0f%%" % (name, start // 50, (start + 500) // 50, peak, depth * 100))
print("\nloudness pulsing:")
pulses(x[:, 0], "left only")
pulses(mono, "both together")
