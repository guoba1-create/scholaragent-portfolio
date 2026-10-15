param(
    [int]$Port = 8080,
    [string]$EnvFile = "$PSScriptRoot\telegram-local-env.ps1"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

if (Test-Path -LiteralPath $EnvFile) {
    . $EnvFile
    Write-Host "[telegram] Loaded env file: $EnvFile"
} else {
    Write-Warning "[telegram] Env file not found: $EnvFile"
    Write-Warning "[telegram] Copy scripts/telegram-local-env.example.ps1 to scripts/telegram-local-env.ps1 first."
}

if (-not $env:TELEGRAM_BOT_TOKEN -or $env:TELEGRAM_BOT_TOKEN -like "*replace_with_botfather_token*") {
    Write-Warning "[telegram] TELEGRAM_BOT_TOKEN is empty or still uses the template placeholder."
}

if ($env:TELEGRAM_POLLING_ENABLED -ne "true") {
    Write-Warning "[telegram] TELEGRAM_POLLING_ENABLED is not true. The bot will not consume Telegram messages."
}

if (-not $env:PUBLIC_APP_BASE_URL -or $env:PUBLIC_APP_BASE_URL -like "*your-public-domain*") {
    Write-Warning "[telegram] PUBLIC_APP_BASE_URL should be a public URL used in Workbench links."
}

if ($env:TELEGRAM_PROXY_ENABLED -eq "true") {
    if (-not $env:TELEGRAM_PROXY_HOST -or -not $env:TELEGRAM_PROXY_PORT) {
        Write-Warning "[telegram] TELEGRAM_PROXY_ENABLED=true but TELEGRAM_PROXY_HOST/PORT is missing."
    } else {
        Write-Host "[telegram] Proxy enabled: $($env:TELEGRAM_PROXY_TYPE)://$($env:TELEGRAM_PROXY_HOST):$($env:TELEGRAM_PROXY_PORT)"
    }
}

Set-Location $repoRoot
Write-Host "[telegram] Starting Spring Boot on http://localhost:$Port"
Write-Host "[telegram] Status endpoint: http://localhost:$Port/api/telegram/status"
.\mvnw.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port"
