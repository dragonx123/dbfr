"""Speech-to-text via faster-whisper, CUDA-accelerated on the RTX 4070 Super."""

from __future__ import annotations

import logging

import numpy as np

logger = logging.getLogger("jarvis.stt")


class WhisperSTT:
    def __init__(self, cfg):
        from faster_whisper import WhisperModel

        self.cfg = cfg
        device = cfg.stt.get("device", "cuda")
        compute_type = cfg.stt.get("compute_type", "float16")
        try:
            self.model = WhisperModel(cfg.stt.model, device=device, compute_type=compute_type)
        except Exception as exc:  # noqa: BLE001 - fall back to CPU if CUDA/cuDNN isn't set up
            logger.warning("Falling back to CPU for STT (GPU init failed: %s)", exc)
            self.model = WhisperModel(cfg.stt.model, device="cpu", compute_type="int8")

    def transcribe(self, audio: np.ndarray) -> str:
        if audio.size == 0:
            return ""
        segments, _info = self.model.transcribe(
            audio,
            language=self.cfg.stt.get("language", "en"),
            vad_filter=True,
        )
        return " ".join(seg.text.strip() for seg in segments).strip()
