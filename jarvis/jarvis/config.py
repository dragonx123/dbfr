"""Loads config/config.yaml into a plain, dotted-access-friendly object."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import yaml

PROJECT_ROOT = Path(__file__).resolve().parent.parent


class Box(dict):
    """A dict that also supports attribute access, recursively."""

    def __getattr__(self, item: str) -> Any:
        try:
            value = self[item]
        except KeyError as exc:
            raise AttributeError(item) from exc
        if isinstance(value, dict) and not isinstance(value, Box):
            value = Box(value)
            self[item] = value
        return value

    def __setattr__(self, key: str, value: Any) -> None:
        self[key] = value


def load_config(path: str | Path | None = None) -> Box:
    """Load config/config.yaml, resolving relative paths against the project root."""
    config_path = Path(path) if path else PROJECT_ROOT / "config" / "config.yaml"
    if not config_path.exists():
        raise FileNotFoundError(
            f"Config file not found at {config_path}. "
            "Copy config/config.yaml and adjust it, or pass --config <path>."
        )

    with open(config_path, "r", encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}

    cfg = Box(raw)

    # Resolve a couple of paths that are conventionally relative to the project root.
    sp_file = cfg.get("persona", {}).get("system_prompt_file")
    if sp_file and not os.path.isabs(sp_file):
        cfg["persona"]["system_prompt_file"] = str(PROJECT_ROOT / sp_file)

    db_path = cfg.get("memory", {}).get("db_path")
    if db_path and not os.path.isabs(db_path):
        cfg["memory"]["db_path"] = str(PROJECT_ROOT / db_path)

    return cfg


def read_system_prompt(cfg: Box) -> str:
    path = cfg.persona.system_prompt_file
    try:
        return Path(path).read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        return f"You are {cfg.persona.get('name', 'Jarvis')}, a helpful local AI assistant."
