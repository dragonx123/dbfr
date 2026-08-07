# Jarvis setup for Windows (RTX 4070 SUPER)
# Run from the jarvis/ directory in PowerShell:  .\scripts\setup_windows.ps1

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot

Write-Host "== 1/5: Checking Ollama ==" -ForegroundColor Cyan
if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
    Write-Host "Ollama not found. Download and install it from https://ollama.com/download/windows" -ForegroundColor Yellow
    Write-Host "Then re-run this script." -ForegroundColor Yellow
    exit 1
}

Write-Host "== 2/5: Pulling the default local model (llama3.1:8b-instruct-q4_K_M) ==" -ForegroundColor Cyan
ollama pull llama3.1:8b-instruct-q4_K_M

Write-Host "== 3/5: Creating Python virtual environment ==" -ForegroundColor Cyan
Set-Location $RepoRoot
if (-not (Test-Path ".venv")) {
    python -m venv .venv
}
& .\.venv\Scripts\Activate.ps1

Write-Host "== 4/5: Installing Python dependencies ==" -ForegroundColor Cyan
python -m pip install --upgrade pip
pip install -r requirements.txt

Write-Host "== 5/5: Downloading a Piper TTS voice (en_US-lessac-medium) ==" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path "models\piper" | Out-Null
$base = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium"
Invoke-WebRequest -Uri "$base/en_US-lessac-medium.onnx" -OutFile "models\piper\en_US-lessac-medium.onnx"
Invoke-WebRequest -Uri "$base/en_US-lessac-medium.onnx.json" -OutFile "models\piper\en_US-lessac-medium.onnx.json"

Write-Host ""
Write-Host "Setup complete! Make sure the Ollama app/service is running, then:" -ForegroundColor Green
Write-Host "  .\.venv\Scripts\Activate.ps1"
Write-Host "  python -m jarvis.main            # voice mode"
Write-Host "  python -m jarvis.main --text     # text mode"
