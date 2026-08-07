"""Push-to-talk microphone recording via sounddevice.

Default UX: press ENTER to start recording, press ENTER again to stop.
This needs zero OS-level permissions and works identically on Windows,
macOS, and Linux from a normal terminal.
"""

from __future__ import annotations

import threading

import numpy as np
import sounddevice as sd


def record_until_enter(sample_rate: int = 16000) -> np.ndarray:
    """Record mono float32 audio from the default mic until the user presses ENTER."""
    frames: list[np.ndarray] = []
    stop_event = threading.Event()

    def callback(indata, _frame_count, _time_info, _status):
        if not stop_event.is_set():
            frames.append(indata.copy())

    input("🎙️  Press ENTER to start talking...")
    print("🔴 Recording... press ENTER to stop.")

    stream = sd.InputStream(samplerate=sample_rate, channels=1, dtype="float32", callback=callback)
    with stream:
        input()  # blocks until ENTER is pressed again
    stop_event.set()

    if not frames:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(frames, axis=0).reshape(-1)
