# Build and start the independent Hytale AI NPC Docker brain.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if (-not (Test-Path (Join-Path $Root ".env"))) {
    Write-Host "No .env — running init-env.ps1"
    & (Join-Path $PSScriptRoot "init-env.ps1")
}

New-Item -ItemType Directory -Force -Path (Join-Path $Root "data") | Out-Null

docker compose up -d --build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Brain HTTP:  http://127.0.0.1:8780/health"
Write-Host "Status:      http://127.0.0.1:8780/status"
Write-Host "WebSocket:   ws://127.0.0.1:8766  (Hytale plugin bridge)"
Write-Host "Logs:        docker logs -f hytale-pest-npc-brain"
