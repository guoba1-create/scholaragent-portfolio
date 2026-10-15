# Copy to telegram-local-env.ps1. Load local-env.ps1 first for shared settings.
. "$PSScriptRoot\local-env.ps1"
$env:TELEGRAM_POLLING_ENABLED = "true"
$env:TELEGRAM_BOT_TOKEN = "replace-me"
$env:TELEGRAM_PROXY_ENABLED = "false"
$env:TELEGRAM_PROXY_HOST = "127.0.0.1"
$env:TELEGRAM_PROXY_PORT = "7890"
$env:PUBLIC_APP_BASE_URL = "http://localhost:8080"
