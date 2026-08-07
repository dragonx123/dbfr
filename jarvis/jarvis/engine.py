"""JarvisEngine — the assistant's core logic (LLM + tools + memory + voice),
shared by both the terminal (main.py) and the web dashboard (server.py) so
there's exactly one place that owns a conversation turn.
"""

from __future__ import annotations

import logging
from typing import Callable

from .config import Box, load_config, read_system_prompt
from .llm import OllamaClient
from .memory import History
from .tools import time_tools

logger = logging.getLogger("jarvis.engine")

ToolCallHook = Callable[[str, dict], None]


class JarvisEngine:
    def __init__(self, cfg: Box | None = None, config_path: str | None = None, load_tts: bool = True):
        self.cfg = cfg or load_config(config_path)
        self.name = self.cfg.persona.get("name", "Jarvis")
        self.system_prompt = read_system_prompt(self.cfg)
        self.llm = OllamaClient(self.cfg)
        self.history = History(self.cfg)

        self.tts = None
        if load_tts:
            try:
                from .tts import PiperTTS

                self.tts = PiperTTS(self.cfg)
            except Exception as exc:  # noqa: BLE001
                logger.warning("TTS unavailable (%s) — replies will be text-only.", exc)

        time_tools.set_announce_callback(self.speak if self.tts else print)

        self._stt = None  # lazily created — see stt property

    @property
    def stt(self):
        if self._stt is None:
            from .stt import WhisperSTT

            self._stt = WhisperSTT(self.cfg)
        return self._stt

    def build_messages(self, user_text: str) -> list[dict]:
        messages = [{"role": "system", "content": self.system_prompt}]
        messages.extend(self.history.recent())
        messages.append({"role": "user", "content": user_text})
        return messages

    def respond(self, user_text: str, on_tool_call: ToolCallHook | None = None) -> str:
        """Run one full conversation turn and return the assistant's reply text.
        Does NOT speak the reply — call .speak() explicitly if you want audio.
        """
        self.history.add("user", user_text)
        messages = self.build_messages(user_text)
        reply, _ = self.llm.chat(messages, on_tool_call=on_tool_call)
        reply = reply or "..."
        self.history.add("assistant", reply)
        return reply

    def speak(self, text: str) -> None:
        if self.tts:
            self.tts.speak(text)

    def close(self) -> None:
        self.history.close()
