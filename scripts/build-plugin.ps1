# Build PestAiNpc.jar and copy into %APPDATA%\Hytale\UserData\Mods\
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Plugin = Join-Path $Root "hytale-plugin"
Set-Location $Plugin

if (Test-Path "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot") {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
}

.\gradlew.bat shadowJar --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jar = Get-ChildItem (Join-Path $Plugin "build\libs\PestAiNpc-*.jar") |
    Where-Object { $_.Name -notmatch "sources|javadoc" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw "No PestAiNpc jar found under build/libs"
}

$mods = Join-Path $env:APPDATA "Hytale\UserData\Mods"
New-Item -ItemType Directory -Force -Path $mods | Out-Null
Copy-Item $jar.FullName $mods -Force
Write-Host "Installed $($jar.Name) → $mods"
Write-Host "Restart Hytale world after installing. Keep Docker brain on :8766."
