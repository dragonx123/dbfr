"""Plays raw PCM audio (as produced by the Piper TTS engine) through the
default output device.
"""

from __future__ import annotations

import numpy as np
import sounddevice as sd


def play_pcm(audio: np.ndarray, sample_rate: int) -> None:
    sd.play(audio, samplerate=sample_rate)
    sd.wait()
