"""Optional global-hotkey push-to-talk (hold-to-record), as an alternative
to the default ENTER-based recorder in jarvis/audio/recorder.py.

Needs the `pynput` package (already in requirements.txt). On Linux this
may need to run with access to the input group / X11 session; on Windows
some setups need Administrator to capture keys system-wide. If that's a
hassle, just use the default `mode: push_to_talk` in config.yaml instead.
"""

from __future__ import annotations

import threading

import numpy as np
import sounddevice as sd
from pynput import keyboard


def record_while_hotkey_held(hotkey: str = "<ctrl>+<space>", sample_rate: int = 16000) -> np.ndarray:
    frames: list[np.ndarray] = []
    recording = threading.Event()
    done = threading.Event()

    def callback(indata, _frames, _time_info, _status):
        if recording.is_set():
            frames.append(indata.copy())

    combo = keyboard.HotKey.parse(hotkey)
    current = set()

    def on_press(key):
        current.add(key)
        if all(c in current for c in combo) and not recording.is_set():
            recording.set()
            print("🔴 Recording (hotkey held)...")

    def on_release(key):
        current.discard(key)
        if recording.is_set() and not all(c in current for c in combo):
            recording.clear()
            done.set()
            return False  # stop listener

    listener = keyboard.Listener(on_press=on_press, on_release=on_release)
    stream = sd.InputStream(samplerate=sample_rate, channels=1, dtype="float32", callback=callback)

    print(f"Hold {hotkey} to talk...")
    with stream:
        listener.start()
        done.wait()
        listener.stop()

    if not frames:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(frames, axis=0).reshape(-1)
