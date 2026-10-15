#!/usr/bin/env python3
"""Generate the bundled drum samples for Beatster from scratch.

Every sound is synthesised procedurally (sine/noise + envelopes + simple filters),
so the resulting WAV files carry no third-party rights. The output is dedicated to
the public domain under CC0 1.0 (see the repository README).

Usage:  python3 tools/samples/generate_samples.py [output_dir]
Default output dir: app/src/main/assets/samples
"""
from __future__ import annotations

import math
import os
import struct
import sys
import wave

import numpy as np

SR = 44_100
RNG = np.random.default_rng(0xBEA7)  # fixed seed -> reproducible files


# ---------------------------------------------------------------- helpers
def t(seconds: float) -> np.ndarray:
    return np.arange(int(SR * seconds)) / SR


def env_exp(n: int, tau: float) -> np.ndarray:
    """Exponential decay envelope, tau in seconds."""
    return np.exp(-np.arange(n) / (SR * tau))


def env_attack(sig: np.ndarray, ms: float) -> np.ndarray:
    a = max(1, int(SR * ms / 1000))
    ramp = np.linspace(0.0, 1.0, a)
    out = sig.copy()
    out[:a] *= ramp
    return out


def biquad(sig: np.ndarray, kind: str, f0: float, q: float = 0.707) -> np.ndarray:
    """RBJ cookbook biquad (lowpass / highpass / bandpass), direct form I."""
    w0 = 2 * math.pi * f0 / SR
    cosw, sinw = math.cos(w0), math.sin(w0)
    alpha = sinw / (2 * q)
    if kind == "lowpass":
        b0, b1, b2 = (1 - cosw) / 2, 1 - cosw, (1 - cosw) / 2
    elif kind == "highpass":
        b0, b1, b2 = (1 + cosw) / 2, -(1 + cosw), (1 + cosw) / 2
    elif kind == "bandpass":
        b0, b1, b2 = alpha, 0.0, -alpha
    else:
        raise ValueError(kind)
    a0 = 1 + alpha
    a1, a2 = -2 * cosw, 1 - alpha
    b0, b1, b2, a1, a2 = (x / a0 for x in (b0, b1, b2, a1, a2))
    y = np.zeros_like(sig)
    x1 = x2 = y1 = y2 = 0.0
    for i, x0 in enumerate(sig):
        y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        y[i] = y0
        x2, x1, y2, y1 = x1, x0, y1, y0
    return y


def noise(seconds: float) -> np.ndarray:
    return RNG.uniform(-1.0, 1.0, int(SR * seconds))


def soft_clip(sig: np.ndarray, drive: float) -> np.ndarray:
    return np.tanh(sig * drive) / math.tanh(drive)


def normalize(sig: np.ndarray, peak_db: float = -1.0) -> np.ndarray:
    peak = np.max(np.abs(sig)) or 1.0
    return sig / peak * (10 ** (peak_db / 20))


def fade_out(sig: np.ndarray, ms: float = 5.0) -> np.ndarray:
    n = min(len(sig), max(1, int(SR * ms / 1000)))
    out = sig.copy()
    out[-n:] *= np.linspace(1.0, 0.0, n)
    return out


def write_wav(path: str, sig: np.ndarray) -> None:
    sig = fade_out(normalize(sig))
    pcm = np.clip(np.round(sig * 32767), -32768, 32767).astype("<i2")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


# ---------------------------------------------------------------- instruments
def kick(length: float, f_start: float, f_end: float, sweep_tau: float,
         body_tau: float, click: float, drive: float) -> np.ndarray:
    tt = t(length)
    freq = f_end + (f_start - f_end) * np.exp(-tt / sweep_tau)
    phase = 2 * math.pi * np.cumsum(freq) / SR
    body = np.sin(phase) * env_exp(len(tt), body_tau)
    transient = biquad(noise(length), "highpass", 2500) * env_exp(len(tt), 0.004) * click
    return soft_clip(body + transient, drive)


def sub_808(length: float, f0: float, glide: float, tau: float, drive: float) -> np.ndarray:
    tt = t(length)
    freq = f0 * (1 + glide * np.exp(-tt / 0.05))
    phase = 2 * math.pi * np.cumsum(freq) / SR
    body = np.sin(phase) + 0.15 * np.sin(2 * phase)
    body = soft_clip(body, drive) * env_exp(len(tt), tau)
    thump = np.sin(2 * math.pi * np.cumsum(f0 * 4 * np.exp(-tt / 0.02)) / SR) * env_exp(len(tt), 0.03) * 0.5
    return env_attack(body + thump, 1.0)


def snare() -> np.ndarray:
    length = 0.32
    tt = t(length)
    n = len(tt)
    tone = (np.sin(2 * math.pi * 185 * tt) + 0.6 * np.sin(2 * math.pi * 330 * tt)) * env_exp(n, 0.06)
    wires = biquad(noise(length), "bandpass", 3200, 0.6) * env_exp(n, 0.11)
    snap = biquad(noise(length), "highpass", 6000) * env_exp(n, 0.015)
    return soft_clip(0.8 * tone + 1.2 * wires + 0.6 * snap, 1.5)


def clap() -> np.ndarray:
    length = 0.45
    n = int(SR * length)
    out = np.zeros(n)
    band = biquad(noise(length), "bandpass", 1800, 0.9)
    for k, offset_ms in enumerate((0, 11, 23, 36)):
        start = int(SR * offset_ms / 1000)
        burst = env_exp(n - start, 0.012 if k < 3 else 0.09)
        out[start:] += band[: n - start] * burst * (0.7 if k < 3 else 1.0)
    return out


def crash() -> np.ndarray:
    length = 3.2
    tt = t(length)
    n = len(tt)
    partials = np.zeros(n)
    for f, amp in ((523, 1.0), (791, 0.8), (1187, 0.7), (1663, 0.6), (2371, 0.5), (3449, 0.4), (4969, 0.3)):
        partials += amp * np.sin(2 * math.pi * f * tt + RNG.uniform(0, 2 * math.pi)) * env_exp(n, 0.9 + 0.3 * amp)
    wash = biquad(noise(length), "highpass", 4000) * env_exp(n, 0.75)
    hit = biquad(noise(length), "highpass", 2000) * env_exp(n, 0.02) * 2
    return 0.35 * partials + 1.0 * wash + hit


def hat(length: float, tau: float, cutoff: float, metal: float) -> np.ndarray:
    tt = t(length)
    n = len(tt)
    # six detuned square-ish partials give the metallic ring of a hi-hat
    ring = np.zeros(n)
    for f in (3137, 4210, 5523, 6791, 8137, 9631):
        ring += np.sign(np.sin(2 * math.pi * f * tt))
    ring = biquad(ring / 6, "highpass", cutoff)
    hiss = biquad(noise(length), "highpass", cutoff)
    sig = (metal * ring + hiss) * env_exp(n, tau)
    return biquad(sig, "lowpass", 16000)


# ---------------------------------------------------------------- catalogue
SAMPLES = {
    "kicks/1.wav": lambda: kick(0.55, 180, 48, 0.035, 0.14, 0.9, 2.0),
    "kicks/2.wav": lambda: kick(0.35, 240, 55, 0.02, 0.07, 1.4, 3.0),
    "kicks/3.wav": lambda: kick(0.7, 140, 42, 0.05, 0.22, 0.5, 1.4),
    "808/1.wav": lambda: sub_808(1.6, 55.0, 2.5, 0.55, 2.5),   # A1
    "808/2.wav": lambda: sub_808(1.2, 49.0, 3.0, 0.40, 3.5),   # G1
    "808/3.wav": lambda: sub_808(2.0, 41.2, 2.0, 0.70, 2.0),   # E1
    "snare/1.wav": snare,
    "clap/1.wav": clap,
    "crash/1.wav": crash,
    "hats/1.wav": lambda: hat(0.09, 0.020, 7000, 0.6),   # closed, tight
    "hats/2.wav": lambda: hat(0.12, 0.030, 6000, 0.8),   # closed
    "hats/3.wav": lambda: hat(0.15, 0.040, 5000, 1.0),   # closed, looser
    "hats/4.wav": lambda: hat(0.08, 0.015, 9000, 0.3),   # closed, soft tick
    "hats/5.wav": lambda: hat(0.55, 0.16, 5500, 0.9),    # open
    "hats/6.wav": lambda: hat(0.8, 0.25, 5000, 1.0),     # open, long
    "hats/7.wav": lambda: hat(0.4, 0.11, 6500, 0.7),     # half-open
}


def main() -> None:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "samples")
    for rel, make in SAMPLES.items():
        path = os.path.normpath(os.path.join(out_dir, rel))
        write_wav(path, make())
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
