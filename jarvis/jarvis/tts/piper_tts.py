"""Text-to-speech via Piper (fast, fully local, CPU is plenty).

Shells out to the `piper` CLI (installed by `pip install piper-tts`) rather
than piper's python API, since the CLI interface has stayed stable across
piper-tts releases while the python API has not.
"""

from __future__ import annotations

import json
import logging
import shutil
import subprocess
from pathlib import Path

import numpy as np

logger = logging.getLogger("jarvis.tts")

MODELS_DIR = Path(__file__).resolve().parent.parent.parent / "models" / "piper"


class PiperTTS:
    def __init__(self, cfg):
        self.cfg = cfg
        if shutil.which("piper") is None:
            raise FileNotFoundError(
                "The 'piper' executable was not found on PATH. "
                "Run `pip install piper-tts` and re-open your shell, or run scripts/setup_*."
            )

        voice = cfg.tts.voice
        self.model_path = MODELS_DIR / f"{voice}.onnx"
        self.config_path = MODELS_DIR / f"{voice}.onnx.json"
        if not self.model_path.exists() or not self.config_path.exists():
            raise FileNotFoundError(
                f"Piper voice files not found for '{voice}' in {MODELS_DIR}. "
                "Run scripts/setup_windows.ps1 or scripts/setup_linux.sh to download a voice, "
                "or grab one manually from https://github.com/rhasspy/piper/releases (voices repo)."
            )

        with open(self.config_path, "r", encoding="utf-8") as f:
            voice_cfg = json.load(f)
        self.sample_rate = voice_cfg.get("audio", {}).get("sample_rate", 22050)

    def synthesize(self, text: str) -> tuple[np.ndarray, int]:
        """Returns (float32 mono PCM in [-1, 1], sample_rate)."""
        if not text.strip():
            return np.zeros(0, dtype=np.float32), self.sample_rate

        proc = subprocess.run(
            ["piper", "--model", str(self.model_path), "--config", str(self.config_path), "--output-raw"],
            input=text.encode("utf-8"),
            capture_output=True,
            timeout=60,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"piper failed: {proc.stderr.decode(errors='ignore')}")

        pcm_i16 = np.frombuffer(proc.stdout, dtype=np.int16)
        pcm_f32 = pcm_i16.astype(np.float32) / 32768.0
        return pcm_f32, self.sample_rate

    def speak(self, text: str) -> None:
        from ..audio.player import play_pcm

        try:
            audio, sr = self.synthesize(text)
            if audio.size:
                play_pcm(audio, sr)
        except Exception:  # noqa: BLE001
            logger.exception("TTS failed; falling back to text-only output")
