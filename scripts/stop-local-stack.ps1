param([switch]$StopDocker)

$repoRoot = Split-Path -Parent $PSScriptRoot
$pidRoot = Join-Path $repoRoot ".run"
foreach ($name in @("java", "python")) {
    $pidFile = Join-Path $pidRoot "$name.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $processId = Get-Content -LiteralPath $pidFile | Select-Object -First 1
        if ($processId) {
            Stop-Process -Id ([int]$processId) -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $pidFile -Force
    }
}

if ($StopDocker) {
    Push-Location $repoRoot
    try {
        docker compose down
    } finally {
        Pop-Location
    }
}
