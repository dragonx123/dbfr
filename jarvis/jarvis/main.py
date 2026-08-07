"""Jarvis entrypoint.

    python -m jarvis.main            # voice mode (push-to-talk)
    python -m jarvis.main --text     # type instead of speak
    python -m jarvis.main --once "what's the weather in Austin?"
"""

from __future__ import annotations

import argparse
import logging
import sys

from .config import load_config, read_system_prompt
from .llm import OllamaClient
from .memory import History
from .tools import time_tools

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("jarvis")


def build_messages(system_prompt: str, history: History, user_text: str) -> list[dict]:
    messages = [{"role": "system", "content": system_prompt}]
    messages.extend(history.recent())
    messages.append({"role": "user", "content": user_text})
    return messages


def run(args: argparse.Namespace) -> None:
    cfg = load_config(args.config)
    system_prompt = read_system_prompt(cfg)
    llm = OllamaClient(cfg)
    history = History(cfg)

    tts = None
    if not args.text:
        try:
            from .tts import PiperTTS

            tts = PiperTTS(cfg)
        except Exception as exc:  # noqa: BLE001
            logger.warning("TTS unavailable (%s) — replies will be text-only.", exc)

    # Let background timers speak/print even outside the main turn.
    time_tools.set_announce_callback(tts.speak if tts else print)

    stt = None
    if not args.text and not args.once:
        try:
            from .stt import WhisperSTT

            stt = WhisperSTT(cfg)
        except Exception as exc:  # noqa: BLE001
            logger.warning("STT unavailable (%s) — falling back to typed input.", exc)

    name = cfg.persona.get("name", "Jarvis")
    print(f"{name} is online. Model: {cfg.llm.model} | Voice input: {'on' if stt else 'off (typing)'}")

    def one_turn(user_text: str) -> str:
        print(f"You: {user_text}")
        history.add("user", user_text)
        messages = build_messages(system_prompt, history, user_text)

        def on_tool_call(tool_name, tool_args):
            print(f"  -> using tool: {tool_name}({tool_args})")

        reply, _ = llm.chat(messages, on_tool_call=on_tool_call)
        reply = reply or "..."
        print(f"{name}: {reply}")
        history.add("assistant", reply)
        if tts:
            tts.speak(reply)
        return reply

    if args.once:
        one_turn(args.once)
        history.close()
        return

    try:
        while True:
            if stt:
                from .audio.recorder import record_until_enter

                audio = record_until_enter(sample_rate=cfg.stt.get("sample_rate", 16000))
                text = stt.transcribe(audio)
                if not text:
                    print("(didn't catch that — try again)")
                    continue
            else:
                text = input("You: ").strip()

            if not text:
                continue
            if text.lower() in {"quit", "exit", "goodbye jarvis"}:
                print(f"{name}: Goodbye!")
                if tts:
                    tts.speak("Goodbye!")
                break

            one_turn(text)
    except (KeyboardInterrupt, EOFError):
        print("\nShutting down.")
    finally:
        history.close()


def main():
    parser = argparse.ArgumentParser(description="Jarvis — a local, offline-first AI assistant.")
    parser.add_argument("--config", default=None, help="Path to config.yaml (default: config/config.yaml)")
    parser.add_argument("--text", action="store_true", help="Type instead of using the microphone/speaker.")
    parser.add_argument("--once", metavar="TEXT", help="Send a single message and exit (no loop, no mic).")
    args = parser.parse_args()

    try:
        run(args)
    except FileNotFoundError as exc:
        print(f"Setup problem: {exc}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
