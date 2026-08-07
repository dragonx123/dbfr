"""Jarvis entrypoint.

    python -m jarvis.main            # voice mode (push-to-talk)
    python -m jarvis.main --text     # type instead of speak
    python -m jarvis.main --once "what's the weather in Austin?"

For the web dashboard instead of the terminal, see jarvis/server.py
(`python -m jarvis.server`).
"""

from __future__ import annotations

import argparse
import logging
import sys

from .engine import JarvisEngine

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("jarvis")


def run(args: argparse.Namespace) -> None:
    engine = JarvisEngine(config_path=args.config, load_tts=not args.text)

    stt = None
    if not args.text and not args.once:
        try:
            stt = engine.stt  # triggers lazy load, may raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("STT unavailable (%s) — falling back to typed input.", exc)

    print(
        f"{engine.name} is online. Model: {engine.cfg.llm.model} | "
        f"Voice input: {'on' if stt else 'off (typing)'}"
    )

    def one_turn(user_text: str) -> str:
        print(f"You: {user_text}")
        reply = engine.respond(
            user_text, on_tool_call=lambda n, a: print(f"  -> using tool: {n}({a})")
        )
        print(f"{engine.name}: {reply}")
        engine.speak(reply)
        return reply

    if args.once:
        one_turn(args.once)
        engine.close()
        return

    try:
        while True:
            if stt:
                from .audio.recorder import record_until_enter

                audio = record_until_enter(sample_rate=engine.cfg.stt.get("sample_rate", 16000))
                text = stt.transcribe(audio)
                if not text:
                    print("(didn't catch that — try again)")
                    continue
            else:
                text = input("You: ").strip()

            if not text:
                continue
            if text.lower() in {"quit", "exit", "goodbye jarvis"}:
                print(f"{engine.name}: Goodbye!")
                engine.speak("Goodbye!")
                break

            one_turn(text)
    except (KeyboardInterrupt, EOFError):
        print("\nShutting down.")
    finally:
        engine.close()


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
