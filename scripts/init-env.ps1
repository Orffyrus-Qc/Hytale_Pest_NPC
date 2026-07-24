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
    "OPENAI_API_KEY="
    "OPENAI_BASE_URL=https://api.openai.com/v1"
    "OPENAI_MODEL=gpt-4o-mini"
    "OLLAMA_BASE_URL="
    "OLLAMA_MODEL=llama3.2"
    "NPC_NAME=Pest"
)

Set-Content -Path $EnvFile -Value $lines -Encoding UTF8
Write-Host "Wrote $EnvFile"
Write-Host "HYTALE_HOST_PATH=$HytaleDocker"
