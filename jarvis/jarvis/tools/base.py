"""A tiny tool registry that produces Ollama-compatible function-calling schemas.

Usage:

    @tool(
        name="get_time",
        description="Get the current local date and time.",
        parameters={"type": "object", "properties": {}, "required": []},
    )
    def get_time():
        ...
"""

from __future__ import annotations

import inspect
import json
import logging
from dataclasses import dataclass
from typing import Any, Callable

logger = logging.getLogger("jarvis.tools")


@dataclass
class Tool:
    name: str
    description: str
    parameters: dict
    func: Callable[..., Any]
    requires: str | None = None  # config flag name that must be truthy to enable this tool


registry: dict[str, Tool] = {}


def tool(name: str, description: str, parameters: dict, requires: str | None = None):
    def decorator(func: Callable[..., Any]):
        registry[name] = Tool(name, description, parameters, func, requires)
        return func

    return decorator


def get_tool_schemas(cfg) -> list[dict]:
    """Return Ollama-format tool schemas for tools enabled in the given config."""
    tools_cfg = cfg.get("tools", {}) if cfg else {}
    schemas = []
    for t in registry.values():
        if t.requires and not tools_cfg.get(t.requires, False):
            continue
        schemas.append(
            {
                "type": "function",
                "function": {
                    "name": t.name,
                    "description": t.description,
                    "parameters": t.parameters,
                },
            }
        )
    return schemas


def execute_tool(name: str, arguments: dict | str, cfg=None) -> str:
    """Run a registered tool by name and return a string result (JSON-encoded if not a string)."""
    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments) if arguments else {}
        except json.JSONDecodeError:
            return f"Error: could not parse arguments for tool '{name}': {arguments!r}"

    t = registry.get(name)
    if t is None:
        return f"Error: unknown tool '{name}'"

    if t.requires and cfg is not None and not cfg.get("tools", {}).get(t.requires, False):
        return f"Error: tool '{name}' is disabled in config (tools.{t.requires} is false)"

    try:
        result = t.func(**(arguments or {}), _cfg=cfg) if _accepts_cfg(t.func) else t.func(**(arguments or {}))
    except TypeError as exc:
        return f"Error calling tool '{name}': bad arguments ({exc})"
    except Exception as exc:  # noqa: BLE001 - surface tool failures to the model instead of crashing
        logger.exception("Tool '%s' raised an exception", name)
        return f"Error: tool '{name}' failed: {exc}"

    if isinstance(result, str):
        return result
    try:
        return json.dumps(result)
    except TypeError:
        return str(result)


def _accepts_cfg(func: Callable) -> bool:
    return "_cfg" in inspect.signature(func).parameters
