# Jarvis — a local AI assistant for your PC

A "Jarvis"-style voice/text assistant that runs **entirely on your machine**
via [Ollama](https://ollama.com) — no cloud API, no API keys, no data
leaving your PC. Tuned for an **RTX 4070 SUPER (12 GB VRAM)**, but every
model/setting is configurable.

```
You (mic or keyboard) → faster-whisper (STT, GPU) → Ollama LLM + tools → Piper (TTS)
```

## What it can do out of the box

- Talk to a local LLM (tool-calling capable models like Llama 3.1/3.2, Qwen2.5, Mistral)
- Voice in (faster-whisper, CUDA-accelerated) and voice out (Piper TTS)
- Tools: current time/date, timers & reminders, weather (Open-Meteo, no key),
  web search (DuckDuckGo, no key), launch applications, set system volume,
  take a screenshot, and an opt-in allowlisted shell command tool
- Remembers recent conversation across restarts (local SQLite)
- Text-only mode if you don't want to deal with mic/speakers
- A live web HUD dashboard (`jarvis/server.py`) — see below

It's a starting point, built to be easy to extend — see [Adding a tool](#adding-a-tool).

## Web dashboard

A dark, animated HUD you open in a browser: a pulsing core visualizer that
reacts to Jarvis's state (standby / listening / processing / responding),
live CPU / RAM / GPU / VRAM gauges for your 4070 SUPER, a scrolling chat
log, and a feed of every tool call as it happens.

```bash
python -m jarvis.server
# then open http://127.0.0.1:8000
```

It's a FastAPI app that drives the *same* `JarvisEngine` the terminal uses
(`jarvis/engine.py`), so conversation history is shared between the CLI and
the dashboard. You can type into the chat box, or click the mic button to
record in the browser (uses `getUserMedia` + `MediaRecorder`, transcribed
server-side with faster-whisper) — replies still play through your PC's
speakers via Piper, same as the terminal.

Host/port are configurable under `dashboard:` in `config/config.yaml`. It
binds to `127.0.0.1` by default (local-only); only widen that if you know
what you're exposing.

## Why Ollama + these models for a 4070 SUPER

The 4070 SUPER has 12 GB of VRAM. Recommended local models (edit `llm.model`
in `config/config.yaml`):

| Model | VRAM (approx) | Notes |
|---|---|---|
| `llama3.1:8b-instruct-q4_K_M` | ~5 GB | **Default.** Fast, reliable tool-calling, leaves headroom for Whisper on the same GPU. |
| `qwen2.5:14b-instruct-q4_K_M` | ~9 GB | Noticeably smarter, still fits comfortably with a bit of headroom. |
| `mistral-nemo:12b-instruct-2407-q4_K_M` | ~7.5 GB | Good middle ground. |

`faster-whisper` at `medium.en` uses roughly 1–2 GB of VRAM, so any of the
above can run at the same time as speech recognition without spilling out
of your 12 GB.

## Setup

**Prerequisites:** [Ollama](https://ollama.com/download) installed, Python 3.10+,
an NVIDIA driver with CUDA support (already required to run games on the 4070S,
so you almost certainly have it). `piper-tts` and `faster-whisper` will use the
GPU/CPU as configured — the GPU is what makes speech recognition feel instant.

### Windows

```powershell
cd jarvis
.\scripts\setup_windows.ps1
```

### Linux

```bash
cd jarvis
bash scripts/setup_linux.sh
```

Either script will: install/verify Ollama, pull the default model, create a
`.venv`, install Python dependencies, and download a Piper voice.

### Run it

```bash
python -m jarvis.main            # voice mode: press ENTER to talk, ENTER again to stop
python -m jarvis.main --text     # type instead of speaking
python -m jarvis.main --once "what time is it?"   # one-shot, scriptable
python -m jarvis.server          # web dashboard at http://127.0.0.1:8000
```

Say/type `quit` or `exit` to leave the terminal mode.

## Configuration

Everything lives in `config/config.yaml` — model choice, Whisper model size,
TTS voice, push-to-talk vs. global-hotkey mode, memory settings, persona/system
prompt (`prompts/system_prompt.txt`), and which tools are enabled. Comments in
the file explain each option.

Notably: `tools.enable_shell` is **off by default**. Turning it on lets the
model run shell commands on your PC — only enable it if you understand that
risk, and use `tools.shell_allowlist` to restrict it to specific programs.

## Project layout

```
jarvis/
  config/config.yaml       # all settings
  prompts/system_prompt.txt
  jarvis/
    main.py                # terminal entrypoint / conversation loop
    server.py               # web dashboard entrypoint (FastAPI + WebSocket)
    engine.py                 # JarvisEngine — shared by main.py and server.py
    system_stats.py            # CPU/RAM/GPU telemetry for the dashboard gauges
    llm/ollama_client.py         # Ollama chat + tool-calling loop
    stt/whisper_stt.py            # faster-whisper (GPU)
    tts/piper_tts.py                # Piper TTS
    audio/                            # mic recording + playback (terminal mode)
    wake/hotkey.py                     # optional global-hotkey push-to-talk
    tools/                               # time, web/weather, system-control tools
    memory/history.py                     # SQLite conversation history
  web/                        # dashboard frontend (HTML/CSS/JS, no build step)
  scripts/                    # one-shot setup scripts
```

## Adding a tool

Tools live in `jarvis/tools/*.py` and register themselves with a decorator:

```python
from .base import tool

@tool(
    name="my_tool",
    description="What this does, so the model knows when to call it.",
    parameters={
        "type": "object",
        "properties": {"arg": {"type": "string", "description": "..."}},
        "required": ["arg"],
    },
)
def my_tool(arg: str):
    return "some result"  # str, or anything json-serializable
```

Import the new module from `jarvis/tools/__init__.py` and it's automatically
available to the model — no other wiring needed. Add `requires="some_flag"`
to gate it behind a `tools.some_flag: true` entry in `config.yaml`.

## Troubleshooting

- **"model not found" from Ollama** — run `ollama pull <model>` (see table above).
- **STT falls back to CPU** — faster-whisper couldn't initialize CUDA/cuDNN;
  check your NVIDIA driver, or it'll just run a bit slower on CPU.
- **No sound / no mic** — check `sounddevice` sees your devices: `python -c "import sounddevice; print(sounddevice.query_devices())"`.
- **"piper executable not found"** — make sure `pip install piper-tts` completed
  and your venv's `Scripts`/`bin` folder is on PATH (re-activate the venv).
- **Global hotkey mode doesn't capture keys** — some OSes require extra
  permissions for system-wide key capture; the default ENTER-based
  push-to-talk (`wake.mode: push_to_talk`) avoids this entirely.
- **Dashboard GPU gauges show "nvidia-smi not found"** — `nvidia-smi` ships
  with the standard NVIDIA driver, so make sure it's on PATH (`nvidia-smi`
  in a terminal should print your 4070S). CPU/RAM gauges work regardless.
- **Mic button in the dashboard does nothing** — browsers only allow
  microphone access on `localhost`/`127.0.0.1` or HTTPS; open the dashboard
  from the PC it's running on, and allow the mic permission prompt.
