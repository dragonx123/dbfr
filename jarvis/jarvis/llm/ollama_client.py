"""Thin wrapper around the `ollama` python client that drives the
tool-calling loop: ask the model, execute any tool calls it requests,
feed results back, repeat until it gives a final text answer.
"""

from __future__ import annotations

import logging

import ollama

from ..tools import execute_tool, get_tool_schemas

logger = logging.getLogger("jarvis.llm")

MAX_TOOL_ROUNDS = 6  # safety cap so a confused model can't loop forever


class OllamaClient:
    def __init__(self, cfg):
        self.cfg = cfg
        self.client = ollama.Client(host=cfg.llm.host)
        self.model = cfg.llm.model

    def chat(self, messages: list[dict], on_tool_call=None) -> tuple[str, list[dict]]:
        """Run one full turn (including any tool-call rounds).

        Returns (final_text, updated_messages) — `updated_messages` includes
        every assistant/tool message generated along the way, ready to be
        appended to the running conversation.
        """
        tools = get_tool_schemas(self.cfg)
        working = list(messages)
        new_messages: list[dict] = []

        for _round in range(MAX_TOOL_ROUNDS):
            response = self.client.chat(
                model=self.model,
                messages=working,
                tools=tools or None,
                options={
                    "temperature": self.cfg.llm.get("temperature", 0.6),
                    "num_ctx": self.cfg.llm.get("num_ctx", 8192),
                },
                keep_alive=self.cfg.llm.get("keep_alive", "30m"),
            )
            message = response["message"]
            working.append(message)
            new_messages.append(message)

            tool_calls = message.get("tool_calls") or []
            if not tool_calls:
                return message.get("content", ""), new_messages

            for call in tool_calls:
                fn = call["function"]
                name, args = fn["name"], fn.get("arguments", {})
                if on_tool_call:
                    on_tool_call(name, args)
                result = execute_tool(name, args, cfg=self.cfg)
                logger.debug("tool %s(%s) -> %s", name, args, result)
                tool_msg = {"role": "tool", "content": str(result), "name": name}
                working.append(tool_msg)
                new_messages.append(tool_msg)

        return (
            "I tried a few tools but couldn't reach a final answer — could you rephrase that?",
            new_messages,
        )
