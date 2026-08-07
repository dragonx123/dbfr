"""System-control tools: launch apps, adjust volume, screenshots, and an
opt-in, allowlisted shell tool. Everything here acts on the machine Jarvis
runs on — keep `enable_shell` off unless you understand the risk.
"""

from __future__ import annotations

import platform
import shlex
import subprocess
from datetime import datetime
from pathlib import Path

from .base import tool

SYSTEM = platform.system()  # "Windows", "Linux", "Darwin"


@tool(
    name="open_application",
    description=(
        "Open/launch an application or a URL on the user's PC, e.g. 'browser', "
        "'notepad', or an alias configured in config.yaml's tools.app_aliases."
    ),
    parameters={
        "type": "object",
        "properties": {
            "name": {
                "type": "string",
                "description": "The app alias (from config) or a raw path/command to launch.",
            }
        },
        "required": ["name"],
    },
    requires="enable_system_control",
)
def open_application(name: str, _cfg=None):
    aliases = (_cfg or {}).get("tools", {}).get("app_aliases", {}) if _cfg else {}
    command = aliases.get(name, name)

    try:
        if SYSTEM == "Windows":
            import os

            os.startfile(command)  # type: ignore[attr-defined]
        elif SYSTEM == "Darwin":
            subprocess.Popen(["open"] + shlex.split(command) if " " in command else ["open", command])
        else:  # Linux and friends
            subprocess.Popen(shlex.split(command))
        return f"Launched '{name}'."
    except Exception as exc:  # noqa: BLE001
        return f"Failed to launch '{name}': {exc}"


@tool(
    name="set_system_volume",
    description="Set or mute the system output volume.",
    parameters={
        "type": "object",
        "properties": {
            "level": {
                "type": "integer",
                "description": "Volume percent 0-100. Omit if only 'mute' is set.",
            },
            "mute": {"type": "boolean", "description": "Mute (true) or unmute (false)."},
        },
        "required": [],
    },
    requires="enable_system_control",
)
def set_system_volume(level: int | None = None, mute: bool | None = None):
    try:
        if SYSTEM == "Windows":
            return _set_volume_windows(level, mute)
        elif SYSTEM == "Linux":
            return _set_volume_linux(level, mute)
        elif SYSTEM == "Darwin":
            return _set_volume_macos(level, mute)
        return f"Volume control isn't implemented for {SYSTEM}."
    except Exception as exc:  # noqa: BLE001
        return f"Failed to change volume: {exc}"


def _set_volume_windows(level, mute):
    from ctypes import POINTER, cast

    import comtypes
    from comtypes import CLSCTX_ALL
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

    comtypes.CoInitialize()
    devices = AudioUtilities.GetSpeakers()
    interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    volume = cast(interface, POINTER(IAudioEndpointVolume))
    if mute is not None:
        volume.SetMute(1 if mute else 0, None)
    if level is not None:
        volume.SetMasterVolumeLevelScalar(max(0, min(100, level)) / 100.0, None)
    return f"Volume set: level={level}, mute={mute}"


def _set_volume_linux(level, mute):
    parts = []
    if level is not None:
        subprocess.run(["amixer", "-q", "sset", "Master", f"{max(0, min(100, level))}%"], check=False)
        parts.append(f"level={level}")
    if mute is not None:
        subprocess.run(["amixer", "-q", "sset", "Master", "mute" if mute else "unmute"], check=False)
        parts.append(f"mute={mute}")
    return "Volume set: " + ", ".join(parts) if parts else "Nothing to change."


def _set_volume_macos(level, mute):
    if level is not None:
        subprocess.run(["osascript", "-e", f"set volume output volume {max(0, min(100, level))}"], check=False)
    if mute is not None:
        subprocess.run(["osascript", "-e", f"set volume output muted {'true' if mute else 'false'}"], check=False)
    return f"Volume set: level={level}, mute={mute}"


@tool(
    name="take_screenshot",
    description="Take a screenshot of the user's screen and save it to disk. Returns the saved file path.",
    parameters={"type": "object", "properties": {}, "required": []},
    requires="enable_system_control",
)
def take_screenshot():
    try:
        from PIL import ImageGrab
    except ImportError:
        return "Error: Pillow is required for screenshots. Run: pip install pillow"

    out_dir = Path(__file__).resolve().parent.parent.parent / "screenshots"
    out_dir.mkdir(exist_ok=True)
    path = out_dir / f"screenshot_{datetime.now():%Y%m%d_%H%M%S}.png"

    img = ImageGrab.grab()
    img.save(path)
    return f"Screenshot saved to {path}"


@tool(
    name="run_shell_command",
    description=(
        "Run a shell command on the user's PC and return its output. Only available if "
        "explicitly enabled, and only for commands on the configured allowlist."
    ),
    parameters={
        "type": "object",
        "properties": {"command": {"type": "string", "description": "The shell command to run."}},
        "required": ["command"],
    },
    requires="enable_shell",
)
def run_shell_command(command: str, _cfg=None):
    allowlist = (_cfg or {}).get("tools", {}).get("shell_allowlist", []) if _cfg else []
    program = shlex.split(command)[0] if command.strip() else ""
    if not allowlist:
        return "Error: enable_shell is on but shell_allowlist is empty — refusing to run anything."
    if program not in allowlist:
        return f"Error: '{program}' is not in tools.shell_allowlist. Allowed: {allowlist}"

    result = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=30)
    output = (result.stdout or "") + (result.stderr or "")
    return output.strip()[:4000] or f"(command exited {result.returncode}, no output)"
