# Install PestAiNpc into Hytale Mods (run anytime)
$ErrorActionPreference = "Stop"
$src = Join-Path $PSScriptRoot "hytale-plugin\build\libs\PestAiNpc-0.1.0.jar"
if (-not (Test-Path $src)) {
  $src = Join-Path $PSScriptRoot "PestAiNpc-0.1.0.jar"
}
if (-not (Test-Path $src)) {
  Write-Host "Building jar first..."
  Push-Location (Join-Path $PSScriptRoot "hytale-plugin")
  if (Test-Path "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot") {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
  }
  .\gradlew.bat shadowJar --no-daemon
  Pop-Location
  $src = Join-Path $PSScriptRoot "hytale-plugin\build\libs\PestAiNpc-0.1.0.jar"
}
$mods = Join-Path $env:APPDATA "Hytale\UserData\Mods"
New-Item -ItemType Directory -Force -Path $mods | Out-Null
Copy-Item $src $mods -Force
Copy-Item $src (Join-Path $PSScriptRoot "PestAiNpc-0.1.0.jar") -Force
Write-Host "OK installed:"
Write-Host "  $mods\PestAiNpc-0.1.0.jar"
Write-Host "  Size: $((Get-Item $src).Length) bytes"
Write-Host "Restart Hytale world after install."
