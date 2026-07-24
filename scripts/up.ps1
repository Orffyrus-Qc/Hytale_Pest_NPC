# Build and start Pest stack: local Qwen (Ollama) + Docker brain.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if (-not (Test-Path (Join-Path $Root ".env"))) {
    Write-Host "No .env - running init-env.ps1"
    & (Join-Path $PSScriptRoot "init-env.ps1")
}

New-Item -ItemType Directory -Force -Path (Join-Path $Root "data") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Root "models\ollama") | Out-Null

docker compose up -d --build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Ensure Qwen is present in project models/ folder (no-op if already pulled)
$model = "qwen2.5:3b"
Get-Content (Join-Path $Root ".env") -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_ -match '^\s*OLLAMA_MODEL\s*=\s*(.+)\s*$') {
        $model = $Matches[1].Trim().Trim('"').Trim("'")
    }
}

$hasModel = $false
try {
    $list = docker exec hytale-pest-npc-ollama ollama list 2>$null | Out-String
    if ($list -match [regex]::Escape($model.Split(":")[0])) { $hasModel = $true }
} catch { }

if (-not $hasModel) {
    Write-Host "Qwen model not found in models/ollama — pulling $model ..."
    & (Join-Path $PSScriptRoot "pull-qwen.ps1")
}

Write-Host ""
Write-Host "Ollama:      http://127.0.0.1:11434  (model data: .\models\ollama)"
Write-Host "Brain HTTP:  http://127.0.0.1:8780/health"
Write-Host "Status:      http://127.0.0.1:8780/status"
Write-Host "WebSocket:   ws://127.0.0.1:8766  (Hytale plugin bridge)"
Write-Host "Logs:        docker logs -f hytale-pest-npc-brain"
