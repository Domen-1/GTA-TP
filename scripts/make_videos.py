"""Turns the recorded switch frames into videos with sound (needs numpy and ffmpeg).

    GTA_TELEPORT_RECORD=1 ./gradlew runClientGameTest   # frames + timelines in build/run/clientGameTest/recordings
    python3 scripts/make_videos.py [output dir]           # <name>.mp4 and a small <name>.gif for every recording

The game test plays the switch on a fixed step, so the frames come with their exact times (frames.csv) and the
sounds the mod played with their pitch and volume (sounds.csv). The sound track is mixed from the mod's own .ogg
files the way the game plays them: pitch changes the playback rate, the wind loop follows the recorded wind level.
"""
import csv
import pathlib
import subprocess
import sys
import tempfile
import wave

import numpy as np

RATE = 44100
ROOT = pathlib.Path(__file__).resolve().parent.parent
RECORDINGS = ROOT / "build/run/clientGameTest/recordings"
SOUNDS = ROOT / "src/main/resources/assets/gta_teleport/sounds"


def decode(path):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-f", "f32le", "-ac", "1", "-ar", str(RATE), "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def sound_file(location):
    namespace, path = location.split(":", 1)
    return SOUNDS / f"{path}.ogg"


def resample(signal, rate):
    """Plays the sound faster or slower, like OpenAL pitch does."""
    positions = np.arange(0.0, len(signal) - 1, rate)
    return np.interp(positions, np.arange(len(signal)), signal)


def mix(frames, sounds, length):
    track = np.zeros(int(length * RATE) + RATE)
    start = frames[0][1]
    for time, location, pitch, volume in sounds:
        if location.endswith("/wind"):
            # The game repeats looping sounds to listeners every 10 ticks; the wind is laid from its level below.
            continue
        clip = resample(decode(sound_file(location)), pitch) * volume
        at = int((time - start) * RATE)
        end = min(at + len(clip), len(track))
        track[at:end] += clip[: end - at]

    # The wind loop: volume 0.8 x level and pitch 0.7 + 0.55 x level, as in the mod.
    times = np.array([f[1] - start for f in frames])
    levels = np.array([f[2] for f in frames])
    t = np.arange(len(track)) / RATE
    level = np.interp(t, times, levels, left=0.0, right=0.0)
    wind = decode(SOUNDS / "switch/wind.ogg")
    position = np.cumsum(0.7 + 0.55 * level) % len(wind)
    track += np.interp(position, np.arange(len(wind)), wind) * level * 0.8
    return track / max(np.max(np.abs(track)) / 0.89, 1.0)


def write_wav(path, track):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((np.clip(track, -1.0, 1.0) * 32767.0).astype("<i2").tobytes())


def render(recording, out):
    frames = []
    with open(recording / "frames.csv") as f:
        for name, time, wind in csv.reader(f):
            frames.append((name, float(time), float(wind)))
    sounds = []
    if (recording / "sounds.csv").exists():
        with open(recording / "sounds.csv") as f:
            for time, location, pitch, volume in csv.reader(f):
                sounds.append((float(time), location, float(pitch), float(volume)))
    length = frames[-1][1] - frames[0][1] + 1.0 / 30.0

    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        concat = tmp / "frames.txt"
        with open(concat, "w") as f:
            for i, (name, time, _) in enumerate(frames):
                following = frames[i + 1][1] if i + 1 < len(frames) else time + 1.0 / 30.0
                f.write(f"file '{(recording / name).as_posix()}'\nduration {max(following - time, 0.001):.4f}\n")
            f.write(f"file '{(recording / frames[-1][0]).as_posix()}'\n")
        audio = tmp / "audio.wav"
        write_wav(audio, mix(frames, sounds, length))
        video = out / f"{recording.name}.mp4"
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(concat), "-i", str(audio),
                        "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-preset", "slow", "-crf", "20",
                        "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", str(video)], check=True)
        gif = out / f"{recording.name}.gif"
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(video), "-vf",
                        "fps=12,scale=560:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];"
                        "[b][p]paletteuse=dither=bayer:bayer_scale=4", "-loop", "0", str(gif)], check=True)
    print(f"{video.name} {length:.1f}s, {len(frames)} frames, {len(sounds)} sounds")


def main():
    out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/videos"
    out.mkdir(parents=True, exist_ok=True)
    for recording in sorted(p for p in RECORDINGS.iterdir() if (p / "frames.csv").exists()):
        render(recording, out)


if __name__ == "__main__":
    main()
