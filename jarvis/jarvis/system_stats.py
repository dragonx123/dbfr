"""CPU/RAM/GPU telemetry for the web dashboard's HUD gauges.

GPU stats come from `nvidia-smi` (present with any standard NVIDIA driver
install — no extra Python package needed for an RTX 4070 SUPER). Falls
back gracefully to nulls if it's not on PATH.
"""

from __future__ import annotations

import shutil
import subprocess

try:
    import psutil
except ImportError:  # pragma: no cover
    psutil = None

_NVIDIA_SMI = shutil.which("nvidia-smi")


def get_system_stats() -> dict:
    return {
        "cpu": _cpu_stats(),
        "ram": _ram_stats(),
        "gpu": _gpu_stats(),
    }


def _cpu_stats() -> dict | None:
    if psutil is None:
        return None
    return {"percent": psutil.cpu_percent(interval=None)}


def _ram_stats() -> dict | None:
    if psutil is None:
        return None
    vm = psutil.virtual_memory()
    return {"percent": vm.percent, "used_gb": round(vm.used / 1e9, 1), "total_gb": round(vm.total / 1e9, 1)}


def _gpu_stats() -> dict | None:
    if not _NVIDIA_SMI:
        return None
    try:
        out = subprocess.run(
            [
                _NVIDIA_SMI,
                "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                "--format=csv,noheader,nounits",
            ],
            capture_output=True,
            text=True,
            timeout=3,
        )
        if out.returncode != 0 or not out.stdout.strip():
            return None
        name, util, mem_used, mem_total, temp = [p.strip() for p in out.stdout.strip().splitlines()[0].split(",")]
        return {
            "name": name,
            "percent": float(util),
            "vram_used_gb": round(float(mem_used) / 1024, 1),
            "vram_total_gb": round(float(mem_total) / 1024, 1),
            "temp_c": float(temp),
        }
    except Exception:  # noqa: BLE001
        return None
