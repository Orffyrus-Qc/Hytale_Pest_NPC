# Create .env with this machine's Hytale path and default ports.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $Root ".env"
$Hytale = Join-Path $env:APPDATA "Hytale"
$HytaleDocker = ($Hytale -replace "\\", "/")

if (-not (Test-Path $Hytale)) {
    Write-Warning "Hytale folder not found at $Hytale - set HYTALE_HOST_PATH manually in .env"
}

$lines = @(
    "HYTALE_HOST_PATH=$HytaleDocker"
    "BRAIN_PORT=8766"
    "API_PORT=8780"
    "SIM_MODE=0"
    # Local Qwen inside hytale-pest-npc/models/ollama (Docker service "ollama")
    "OLLAMA_BASE_URL=http://ollama:11434"
    "OLLAMA_MODEL=qwen2.5:3b"
    "OLLAMA_PORT=11434"
    # Cloud LLM optional — leave empty when using local Qwen
    "XAI_API_KEY="
    "OPENAI_API_KEY="
    "OPENAI_BASE_URL="
    "OPENAI_MODEL="
    "NPC_NAME=Pest"
)

Set-Content -Path $EnvFile -Value $lines -Encoding UTF8
Write-Host "Wrote $EnvFile"
Write-Host "HYTALE_HOST_PATH=$HytaleDocker"
