#!/usr/bin/env python3
"""Create legal local A/B stimuli for AudioPlaybackCapture testing.

No copyrighted recordings are included. Output is stereo PCM WAV at 48 kHz.
"""
import argparse
import math
import struct
import wave
from pathlib import Path

RATE = 48_000
DURATION = 12.0


def music_sample(t: float) -> float:
    beat = 60.0 / 100.0
    beat_pos = t % beat
    bar_pos = int(t / (beat * 4)) % 4
    roots = (55.0, 65.41, 73.42, 49.0)
    root = roots[bar_pos]
    bass = 0.19 * math.sin(2 * math.pi * root * t)
    bass += 0.055 * math.sin(2 * math.pi * root * 2 * t)
    kick_env = math.exp(-beat_pos * 18.0)
    kick = 0.23 * kick_env * math.sin(2 * math.pi * (48.0 - 19.0 * beat_pos) * t)
    chord = (261.63, 329.63, 392.0) if bar_pos % 2 == 0 else (220.0, 277.18, 329.63)
    mid = sum(math.sin(2 * math.pi * f * t) for f in chord) * 0.035
    hat_phase = t % (beat / 2.0)
    hat = 0.035 * math.exp(-hat_phase * 95.0) * math.sin(2 * math.pi * 7200.0 * t)
    return max(-0.85, min(0.85, bass + kick + mid + hat))


def render(kind: str, t: float) -> float:
    if kind == "55hz":
        return 0.28 * math.sin(2 * math.pi * 55.0 * t)
    if kind == "1khz":
        return 0.18 * math.sin(2 * math.pi * 1000.0 * t)
    if kind == "combined":
        return 0.23 * math.sin(2 * math.pi * 55.0 * t) + 0.12 * math.sin(2 * math.pi * 1000.0 * t)
    return music_sample(t)


def write(path: Path, kind: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    frames = int(RATE * DURATION)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(RATE)
        block = bytearray()
        for n in range(frames):
            t = n / RATE
            sample = max(-0.95, min(0.95, render(kind, t)))
            value = int(sample * 32767)
            block.extend(struct.pack("<hh", value, value))
        output.writeframes(block)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("bass_only_test_audio"))
    args = parser.parse_args()
    for filename, kind in (
        ("reference_55hz.wav", "55hz"),
        ("reference_1khz.wav", "1khz"),
        ("ab_55hz_plus_1khz.wav", "combined"),
        ("synthetic_music_capture_test.wav", "music"),
    ):
        write(args.output_dir / filename, kind)
        print(args.output_dir / filename)


if __name__ == "__main__":
    main()
