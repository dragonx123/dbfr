#!/usr/bin/env bash
# Jarvis setup for Linux (RTX 4070 SUPER). Run from the jarvis/ directory:
#   bash scripts/setup_linux.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "== 1/5: Checking Ollama =="
if ! command -v ollama >/dev/null 2>&1; then
    echo "Ollama not found. Installing via the official script (curl | sh)..."
    curl -fsSL https://ollama.com/install.sh | sh
fi

echo "== 2/5: Pulling the default local model (llama3.1:8b-instruct-q4_K_M) =="
ollama pull llama3.1:8b-instruct-q4_K_M

echo "== 3/5: Creating Python virtual environment =="
python3 -m venv .venv
source .venv/bin/activate

echo "== 4/5: Installing Python dependencies =="
pip install --upgrade pip
pip install -r requirements.txt

echo "== 5/5: Downloading a Piper TTS voice (en_US-lessac-medium) =="
mkdir -p models/piper
base="https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium"
curl -fL -o models/piper/en_US-lessac-medium.onnx "$base/en_US-lessac-medium.onnx"
curl -fL -o models/piper/en_US-lessac-medium.onnx.json "$base/en_US-lessac-medium.onnx.json"

echo ""
echo "Setup complete! Make sure the ollama service is running (systemctl status ollama, or 'ollama serve'), then:"
echo "  source .venv/bin/activate"
echo "  python -m jarvis.main            # voice mode"
echo "  python -m jarvis.main --text     # text mode"
