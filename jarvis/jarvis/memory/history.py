"""Persistent conversation history in a local SQLite file, so Jarvis
remembers recent context across restarts.
"""

from __future__ import annotations

import sqlite3
import time
from pathlib import Path


class History:
    def __init__(self, cfg):
        self.enabled = cfg.memory.get("enabled", True)
        self.max_turns = cfg.memory.get("max_turns_in_context", 20)
        self.db_path = Path(cfg.memory.db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)

        self.conn = sqlite3.connect(self.db_path)
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                ts REAL NOT NULL
            )
            """
        )
        self.conn.commit()

    def add(self, role: str, content: str) -> None:
        if not self.enabled or not content:
            return
        self.conn.execute(
            "INSERT INTO messages (role, content, ts) VALUES (?, ?, ?)",
            (role, content, time.time()),
        )
        self.conn.commit()

    def recent(self) -> list[dict]:
        """Most recent `max_turns` user/assistant messages, oldest first."""
        if not self.enabled:
            return []
        rows = self.conn.execute(
            "SELECT role, content FROM messages WHERE role IN ('user','assistant') "
            "ORDER BY id DESC LIMIT ?",
            (self.max_turns,),
        ).fetchall()
        return [{"role": role, "content": content} for role, content in reversed(rows)]

    def close(self) -> None:
        self.conn.close()
