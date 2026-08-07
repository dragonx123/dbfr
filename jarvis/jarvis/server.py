"""Jarvis web dashboard — a FastAPI app that serves the HUD frontend and
bridges it to the same JarvisEngine the terminal uses (jarvis/main.py).

    python -m jarvis.server
    -> open http://127.0.0.1:8000
"""

from __future__ import annotations

import asyncio
import logging
import tempfile
import time
from pathlib import Path

from fastapi import FastAPI, File, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .config import load_config
from .engine import JarvisEngine
from .system_stats import get_system_stats

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("jarvis.server")

WEB_DIR = Path(__file__).resolve().parent.parent / "web"
STATS_INTERVAL_S = 1.5

app = FastAPI(title="Jarvis Dashboard")
app.mount("/static", StaticFiles(directory=WEB_DIR / "static"), name="static")

engine: JarvisEngine | None = None
start_time = time.time()


class ConnectionManager:
    def __init__(self):
        self.active: list[WebSocket] = []

    async def connect(self, ws: WebSocket):
        await ws.accept()
        self.active.append(ws)

    def disconnect(self, ws: WebSocket):
        if ws in self.active:
            self.active.remove(ws)

    async def broadcast(self, message: dict):
        dead = []
        for ws in self.active:
            try:
                await ws.send_json(message)
            except Exception:  # noqa: BLE001
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)


manager = ConnectionManager()


@app.on_event("startup")
async def on_startup():
    global engine
    logger.info("Booting Jarvis engine...")
    engine = JarvisEngine(load_tts=True)
    asyncio.create_task(_stats_broadcaster())
    logger.info("Jarvis engine ready (model=%s).", engine.cfg.llm.model)


@app.on_event("shutdown")
async def on_shutdown():
    if engine:
        engine.close()


async def _stats_broadcaster():
    while True:
        try:
            stats = await asyncio.to_thread(get_system_stats)
            await manager.broadcast({"type": "system_stats", **stats})
        except Exception:  # noqa: BLE001
            logger.exception("stats broadcast failed")
        await asyncio.sleep(STATS_INTERVAL_S)


@app.get("/")
async def index():
    return FileResponse(WEB_DIR / "index.html")


@app.get("/api/status")
async def status():
    return {
        "name": engine.name,
        "model": engine.cfg.llm.model,
        "voice_enabled": engine.tts is not None,
        "uptime_s": round(time.time() - start_time),
    }


@app.get("/api/history")
async def history():
    return engine.history.recent()


async def _run_turn(text: str):
    """Runs one full conversation turn off the event loop, broadcasting
    status/tool-call/reply events to every connected dashboard client.
    """
    loop = asyncio.get_running_loop()
    await manager.broadcast({"type": "status", "state": "thinking"})

    def on_tool_call(name, args):
        asyncio.run_coroutine_threadsafe(
            manager.broadcast({"type": "tool_call", "name": name, "args": args}), loop
        )

    try:
        reply = await asyncio.to_thread(engine.respond, text, on_tool_call)
    except Exception as exc:  # noqa: BLE001
        logger.exception("engine.respond failed")
        await manager.broadcast({"type": "error", "text": str(exc)})
        await manager.broadcast({"type": "status", "state": "idle"})
        return

    await manager.broadcast({"type": "assistant_message", "text": reply})

    if engine.tts:
        await manager.broadcast({"type": "status", "state": "speaking"})
        await asyncio.to_thread(engine.speak, reply)

    await manager.broadcast({"type": "status", "state": "idle"})


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await manager.connect(ws)
    try:
        while True:
            data = await ws.receive_json()
            if data.get("type") == "user_message":
                text = (data.get("text") or "").strip()
                if not text:
                    continue
                await manager.broadcast({"type": "user_message", "text": text})
                await _run_turn(text)
    except WebSocketDisconnect:
        manager.disconnect(ws)


@app.post("/api/voice")
async def voice_upload(file: UploadFile = File(...)):
    """Accepts a browser-recorded audio clip (webm/opus, wav, etc.), transcribes
    it with faster-whisper, then runs it through the same conversation turn as
    typed messages — broadcast to all connected dashboard clients over /ws.
    """
    suffix = Path(file.filename or "clip.webm").suffix or ".webm"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(await file.read())
        tmp_path = tmp.name

    try:
        text = await asyncio.to_thread(engine.stt.transcribe_file, tmp_path)
    except Exception as exc:  # noqa: BLE001
        logger.exception("voice transcription failed")
        return {"error": f"Transcription failed: {exc}"}
    finally:
        Path(tmp_path).unlink(missing_ok=True)

    if not text:
        return {"error": "Didn't catch that — try again."}

    await manager.broadcast({"type": "user_message", "text": text})
    asyncio.create_task(_run_turn(text))
    return {"text": text}


def main():
    import uvicorn

    cfg = load_config()
    dash_cfg = cfg.get("dashboard", {})
    uvicorn.run(
        "jarvis.server:app",
        host=dash_cfg.get("host", "127.0.0.1"),
        port=dash_cfg.get("port", 8000),
        reload=False,
    )


if __name__ == "__main__":
    main()
