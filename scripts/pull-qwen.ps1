# Pull the project-local Qwen model into ./models/ollama (Ollama volume).
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$model = "qwen2.5:3b"
if (Test-Path (Join-Path $Root ".env")) {
    Get-Content (Join-Path $Root ".env") | ForEach-Object {
        if ($_ -match '^\s*OLLAMA_MODEL\s*=\s*(.+)\s*$') {
            $model = $Matches[1].Trim().Trim('"').Trim("'")
        }
    }
}

New-Item -ItemType Directory -Force -Path (Join-Path $Root "models\ollama") | Out-Null

Write-Host "Starting Ollama service..."
docker compose up -d ollama
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Wait until ollama answers
$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        docker exec hytale-pest-npc-ollama ollama list 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ok = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ok) {
    Write-Error "Ollama container not ready. Check: docker logs hytale-pest-npc-ollama"
    exit 1
}

Write-Host "Pulling model into project folder models/ollama: $model"
Write-Host "(first pull can take several minutes)"
docker exec hytale-pest-npc-ollama ollama pull $model
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "OK. Model stored under: $Root\models\ollama"
Write-Host "Brain uses: OLLAMA_BASE_URL=http://ollama:11434  OLLAMA_MODEL=$model"
docker exec hytale-pest-npc-ollama ollama list
