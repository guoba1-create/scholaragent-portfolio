param(
    [string]$EnvFile = "$PSScriptRoot\local-env.ps1",
    [string]$PythonExecutable = "",
    [switch]$SkipDocker,
    [switch]$SkipPythonInstall
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $repoRoot "python-runtime"
$logRoot = Join-Path $repoRoot "logs"
$pidRoot = Join-Path $repoRoot ".run"
$langGraphDataRoot = Join-Path $repoRoot "data\langgraph"
New-Item -ItemType Directory -Force -Path $logRoot, $pidRoot, $langGraphDataRoot | Out-Null

if (Test-Path -LiteralPath $EnvFile) {
    . $EnvFile
    Write-Host "[stack] Loaded $EnvFile"
} else {
    Write-Warning "[stack] $EnvFile does not exist. Existing process environment variables will be used."
}

$requiredSettings = @(
    @{ Name = "DASHSCOPE_API_KEY"; Hint = "DashScope model and embedding access" },
    @{ Name = "PINECONE_API_KEY"; Hint = "Pinecone vector index access" },
    @{ Name = "PINECONE_INDEX_NAME"; Hint = "Pinecone index name" }
)
$missingSettings = @()
foreach ($setting in $requiredSettings) {
    $value = [Environment]::GetEnvironmentVariable($setting.Name, "Process")
    if ([string]::IsNullOrWhiteSpace($value) -or $value -match "^(replace-me|your-.+|changeme)$") {
        $missingSettings += "$($setting.Name) ($($setting.Hint))"
    }
}
if ($missingSettings.Count -gt 0) {
    $copyCommand = "Copy-Item scripts\local-env.example.ps1 scripts\local-env.ps1"
    throw @"
[stack] Required settings are missing:
 - $($missingSettings -join "`n - ")

Create the local configuration and fill real values:
  $copyCommand

Then run this script again. Infrastructure and application processes were not started.
"@
}

if ([string]::IsNullOrWhiteSpace($env:AGENT_INTERNAL_TOKEN) -or
        $env:AGENT_INTERNAL_TOKEN -match "^(replace|change|your-)") {
    $env:AGENT_INTERNAL_TOKEN = [Guid]::NewGuid().ToString("N") + [Guid]::NewGuid().ToString("N")
    Write-Host "[stack] Generated an in-process Agent runtime token."
}

if (-not $env:LANGGRAPH_CHECKPOINT_PATH) {
    $env:LANGGRAPH_CHECKPOINT_PATH = Join-Path $langGraphDataRoot "checkpoints\langgraph-checkpoints.json"
}
if (-not $env:LANGGRAPH_RUNTIME_STATE_DIR) {
    $env:LANGGRAPH_RUNTIME_STATE_DIR = Join-Path $langGraphDataRoot "runtime-state"
}

if (-not $SkipDocker) {
    Push-Location $repoRoot
    try {
        & docker compose up -d redis zookeeper kafka grobid searxng prometheus grafana
        if ($LASTEXITCODE -ne 0) {
            throw "[stack] docker compose up failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

$venvPython = Join-Path $runtimeRoot ".venv\Scripts\python.exe"
if (-not $PythonExecutable) {
    if (Test-Path -LiteralPath $venvPython) {
        $PythonExecutable = $venvPython
    } else {
        $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
        if (-not $pythonCommand) {
            throw "Python was not found. Install Python 3.11+ or pass -PythonExecutable."
        }
        $PythonExecutable = $pythonCommand.Source
        & $PythonExecutable -m venv (Join-Path $runtimeRoot ".venv")
        $PythonExecutable = $venvPython
    }
}

if (-not $SkipPythonInstall) {
    & $PythonExecutable -m pip install -q -r (Join-Path $runtimeRoot "requirements.txt")
}

$env:PYTHONPATH = Join-Path $runtimeRoot "src"
$pythonOut = Join-Path $logRoot "python-runtime.out.log"
$pythonErr = Join-Path $logRoot "python-runtime.err.log"
$pythonProcess = Start-Process -FilePath $PythonExecutable `
    -ArgumentList @("-m", "langgraph_runtime.main") `
    -WorkingDirectory $runtimeRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $pythonOut -RedirectStandardError $pythonErr
$pythonProcess.Id | Set-Content -LiteralPath (Join-Path $pidRoot "python.pid")

$javaOut = Join-Path $logRoot "spring-boot.out.log"
$javaErr = Join-Path $logRoot "spring-boot.err.log"
$javaProcess = Start-Process -FilePath "powershell.exe" `
    -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $repoRoot "mvnw.ps1"), "-q", "spring-boot:run") `
    -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $javaOut -RedirectStandardError $javaErr
$javaProcess.Id | Set-Content -LiteralPath (Join-Path $pidRoot "java.pid")

Write-Host "[stack] Python PID: $($pythonProcess.Id)"
Write-Host "[stack] Java PID:   $($javaProcess.Id)"
Write-Host "[stack] Waiting for http://localhost:8080/api/health/readiness"

$deadline = (Get-Date).AddMinutes(2)
do {
    Start-Sleep -Seconds 2
    if ($pythonProcess.HasExited) {
        throw "[stack] Python runtime exited during startup. Inspect $pythonErr and $pythonOut."
    }
    if ($javaProcess.HasExited) {
        throw "[stack] Spring Boot exited during startup. Inspect $javaErr and $javaOut."
    }
    try {
        $report = Invoke-RestMethod -Uri "http://localhost:8080/api/health/readiness" -TimeoutSec 3
        if ($report.ready) {
            Write-Host "[stack] READY: http://localhost:8080/app/"
            exit 0
        }
    } catch {
        # The endpoint returns 503 while required dependencies are still starting.
    }
} while ((Get-Date) -lt $deadline)

Write-Warning "[stack] Processes started, but full readiness was not reached in two minutes."
Write-Warning "[stack] Inspect $javaErr, $javaOut, $pythonErr and /api/health/readiness."
exit 1
