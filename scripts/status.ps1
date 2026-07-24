$ErrorActionPreference = "Stop"
try {
    $r = Invoke-RestMethod -Uri "http://127.0.0.1:8780/status" -TimeoutSec 5
    $r | ConvertTo-Json -Depth 8
} catch {
    Write-Host "Brain not reachable on :8780 — is the container up?"
    docker ps --filter "name=hytale-pest-npc-brain"
    exit 1
}
