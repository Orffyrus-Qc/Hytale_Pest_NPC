# Start brain in SIM_MODE (no Hytale plugin required) for learning-loop smoke test.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if (-not (Test-Path (Join-Path $Root ".env"))) {
    & (Join-Path $PSScriptRoot "init-env.ps1")
}

# Force sim mode for this run
$env:SIM_MODE = "1"
# Ensure .env has SIM_MODE=1 temporarily by rewriting line
$EnvPath = Join-Path $Root ".env"
$lines = Get-Content $EnvPath
$lines = $lines | ForEach-Object { if ($_ -match '^\s*SIM_MODE=') { 'SIM_MODE=1' } else { $_ } }
if (-not ($lines -match '^\s*SIM_MODE=')) { $lines += 'SIM_MODE=1' }
Set-Content $EnvPath $lines -Encoding UTF8

New-Item -ItemType Directory -Force -Path (Join-Path $Root "data") | Out-Null
docker compose up -d --build
Write-Host "SIM_MODE brain starting. Watch: docker logs -f hytale-pest-npc-brain"
Write-Host "Status: http://127.0.0.1:8780/status"
