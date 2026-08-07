"""Persistent conversation history in a local SQLite file, so Jarvis
remembers recent context across restarts.

`check_same_thread=False` + an explicit lock because the web dashboard
(jarvis/server.py) runs each conversation turn in a worker thread via
`asyncio.to_thread`, not the thread that constructed this connection.
"""

from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path


class History:
    def __init__(self, cfg):
        self.enabled = cfg.memory.get("enabled", True)
        self.max_turns = cfg.memory.get("max_turns_in_context", 20)
        self.db_path = Path(cfg.memory.db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)

        self._lock = threading.Lock()
        self.conn = sqlite3.connect(self.db_path, check_same_thread=False)
        with self._lock:
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
        with self._lock:
            self.conn.execute(
                "INSERT INTO messages (role, content, ts) VALUES (?, ?, ?)",
                (role, content, time.time()),
            )
            self.conn.commit()

    def recent(self) -> list[dict]:
        """Most recent `max_turns` user/assistant messages, oldest first."""
        if not self.enabled:
            return []
        with self._lock:
            rows = self.conn.execute(
                "SELECT role, content FROM messages WHERE role IN ('user','assistant') "
                "ORDER BY id DESC LIMIT ?",
                (self.max_turns,),
            ).fetchall()
        return [{"role": role, "content": content} for role, content in reversed(rows)]

    def close(self) -> None:
        with self._lock:
            self.conn.close()
