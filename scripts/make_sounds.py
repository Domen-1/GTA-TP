"""Synthesizes the switch sounds into src/main/resources/assets/gta_teleport/sounds/switch (needs numpy and ffmpeg).

    python3 scripts/make_sounds.py
"""
import math
import pathlib
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44100
OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/gta_teleport/sounds/switch"


def time_axis(seconds):
    return np.arange(int(seconds * RATE)) / RATE


def noise(seconds, seed):
    return np.random.default_rng(seed).standard_normal(int(seconds * RATE))


def svf(signal, cutoff, q, mode):
    """Chamberlin state variable filter with a cutoff (Hz) that may change every sample."""
    cutoff = np.broadcast_to(np.asarray(cutoff, dtype=float), signal.shape)
    f = 2.0 * np.sin(np.pi * np.minimum(cutoff, RATE / 6.0) / RATE)
    damp = 1.0 / q
    low = band = 0.0
    out = np.empty_like(signal)
    for i, x in enumerate(signal):
        low += f[i] * band
        high = x - low - damp * band
        band += f[i] * high
        out[i] = low if mode == "low" else band if mode == "band" else high
    return out


def sweep(start, end, seconds, length, curve=1.0):
    """Exponential frequency glide from start to end over `seconds`, then held."""
    t = np.minimum(time_axis(length) / seconds, 1.0) ** curve
    return start * (end / start) ** t


def decay(length, tau, attack=0.004):
    t = time_axis(length)
    return np.minimum(t / attack, 1.0) * np.exp(-t / tau)


def boom(length, start_hz, end_hz, glide, tau, seed):
    t = time_axis(length)
    freq = sweep(start_hz, end_hz, glide, length)
    phase = 2.0 * np.pi * np.cumsum(freq) / RATE
    body = np.sin(phase) + 0.25 * np.sin(2.0 * phase + 0.6)
    click = svf(noise(length, seed), 1400.0, 0.8, "low") * decay(length, 0.022, 0.0008)
    return np.tanh(1.6 * body * decay(length, tau, 0.006)) + 0.5 * click * (t < 0.15)


def reverb(signal, seconds, tau, wet, seed):
    ir = svf(noise(seconds, seed), 2500.0, 0.7, "low") * np.exp(-time_axis(seconds) / tau)
    ir /= np.sqrt(np.sum(ir ** 2))
    n = len(signal) + len(ir)
    size = 1 << (n - 1).bit_length()
    tail = np.fft.irfft(np.fft.rfft(signal, size) * np.fft.rfft(ir, size), size)[: len(signal)]
    return signal + wet * tail


def pad(signal, seconds):
    return np.concatenate([signal, np.zeros(int(seconds * RATE))])


def jump(seed, boom_hz, whoosh_from, whoosh_to):
    length = 1.7
    sub = boom(length, boom_hz, 36.0, 0.55, 0.38, seed)
    # The "fwoom": air rushing past, falling in pitch as the camera brakes.
    air = svf(noise(length, seed + 1), sweep(whoosh_from, whoosh_to, 0.7, length, 0.6), 1.3, "band")
    air *= decay(length, 0.28, 0.012) * 2.2
    rumble = svf(noise(length, seed + 2), 140.0, 0.9, "low") * decay(length, 0.6, 0.01) * 1.6
    mix = 0.9 * sub + air + rumble
    return reverb(mix, 1.4, 0.32, 0.35, seed + 3)


def prep():
    length = 0.62
    t = time_axis(length)
    rise = np.clip(t / 0.31, 0.0, 1.0)
    # A reversed whoosh that sucks in and stops dead where the first jump booms.
    envelope = rise ** 3 * np.where(t < 0.31, 1.0, np.exp(-(t - 0.31) / 0.03))
    air = svf(noise(length, 11), 300.0 * (3200.0 / 300.0) ** rise, 1.6, "band") * envelope * 2.0
    tone = np.sin(2.0 * np.pi * np.cumsum(40.0 + 45.0 * rise) / RATE) * envelope * 0.5
    return reverb(air + tone, 0.8, 0.18, 0.25, 12)


def pan():
    length = 2.6
    t = time_axis(length)
    x = t / length
    bell = np.sin(np.pi * np.clip((x - 0.02) / 0.9, 0.0, 1.0)) ** 2
    center = 380.0 + 1700.0 * np.sin(np.pi * np.clip(x / 0.92, 0.0, 1.0)) ** 1.5
    air = svf(noise(length, 21), center, 1.1, "band") * bell * 1.8
    hiss = svf(noise(length, 22), 5200.0, 0.7, "high") * bell ** 2 * 0.35
    rumble = svf(noise(length, 23), 110.0, 0.8, "low") * bell * 2.0
    return reverb(air + hiss + rumble, 1.2, 0.3, 0.3, 24)


def land():
    length = 2.0
    sub = boom(length, 82.0, 42.0, 0.5, 0.45, 31)
    air = svf(noise(length, 32), sweep(1800.0, 260.0, 0.5, length, 0.7), 1.2, "band") * decay(length, 0.22, 0.01) * 1.8
    shimmer = svf(noise(length, 33), 6500.0, 2.5, "band") * decay(length, 0.45, 0.02) * 0.7
    mix = 0.8 * sub + air + shimmer
    return reverb(mix, 1.6, 0.4, 0.4, 34)


def wind():
    """Four seconds that loop without a seam: the noise is shaped in the frequency domain, so it is periodic."""
    length = 4.0
    n = int(length * RATE)
    spectrum = np.fft.rfft(np.random.default_rng(41).standard_normal(n))
    f = np.fft.rfftfreq(n, 1.0 / RATE)
    shape = 1.0 / np.sqrt(np.maximum(f, 20.0))
    shape *= 1.0 + 3.0 * np.exp(-((np.log(np.maximum(f, 1.0)) - math.log(110.0)) ** 2) / 0.5)
    shape *= 1.0 + 1.5 * np.exp(-((np.log(np.maximum(f, 1.0)) - math.log(700.0)) ** 2) / 0.6)
    shape *= 1.0 / (1.0 + (f / 4000.0) ** 4)
    base = np.fft.irfft(spectrum * shape, n)
    t = np.arange(n) / RATE
    gusts = 1.0 + 0.25 * np.sin(2.0 * np.pi * 0.5 * t) + 0.15 * np.sin(2.0 * np.pi * 0.75 * t + 1.3)
    return base * gusts


def normalize(signal, peak):
    return signal * (peak / np.max(np.abs(signal)))


def fade(signal, fade_in, fade_out):
    out = signal.copy()
    a = int(fade_in * RATE)
    b = int(fade_out * RATE)
    if a:
        out[:a] *= np.linspace(0.0, 1.0, a)
    if b:
        out[-b:] *= np.linspace(1.0, 0.0, b)
    return out


def write(name, signal):
    OUT.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(signal, -1.0, 1.0) * 32767.0).astype("<i2")
    with tempfile.TemporaryDirectory() as tmp:
        wav = pathlib.Path(tmp) / f"{name}.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "5",
                        str(OUT / f"{name}.ogg")], check=True)
    print(f"{name}.ogg {len(signal) / RATE:.2f}s")


def main():
    write("jump1", fade(normalize(jump(100, 92.0, 2600.0, 320.0), 0.9), 0.0, 0.3))
    write("jump2", fade(normalize(jump(200, 100.0, 2300.0, 300.0), 0.9), 0.0, 0.3))
    write("jump3", fade(normalize(jump(300, 86.0, 2900.0, 360.0), 0.9), 0.0, 0.3))
    write("prep", fade(normalize(prep(), 0.6), 0.0, 0.2))
    write("pan", fade(normalize(pan(), 0.7), 0.05, 0.4))
    write("land", fade(normalize(land(), 0.8), 0.0, 0.4))
    write("wind", normalize(wind(), 0.5))


if __name__ == "__main__":
    main()
