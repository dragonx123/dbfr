"""Time, date, and reminder/timer tools. Always enabled — no external calls."""

from __future__ import annotations

import threading
import time
from datetime import datetime

from .base import tool

# Filled in by main.py so timers can speak/print when they fire.
_announce_callback = None


def set_announce_callback(callback):
    global _announce_callback
    _announce_callback = callback


@tool(
    name="get_current_datetime",
    description="Get the current local date and time on the user's PC.",
    parameters={"type": "object", "properties": {}, "required": []},
)
def get_current_datetime():
    now = datetime.now()
    return now.strftime("%A, %B %d, %Y, %I:%M %p")


@tool(
    name="set_timer",
    description=(
        "Set a countdown timer that announces a message when it finishes. "
        "Use for 'remind me in N minutes' or 'set a timer for N seconds' requests."
    ),
    parameters={
        "type": "object",
        "properties": {
            "seconds": {
                "type": "number",
                "description": "How many seconds from now the timer should fire.",
            },
            "message": {
                "type": "string",
                "description": "What to say/announce when the timer fires.",
            },
        },
        "required": ["seconds", "message"],
    },
)
def set_timer(seconds: float, message: str):
    seconds = max(1, float(seconds))

    def fire():
        time.sleep(seconds)
        text = f"Timer done: {message}"
        if _announce_callback:
            _announce_callback(text)
        else:
            print(f"\n[Jarvis timer] {text}")

    threading.Thread(target=fire, daemon=True).start()
    return f"Timer set for {seconds:.0f} seconds. I'll announce: {message!r}"
