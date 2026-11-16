$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
# Validation only: these placeholders are never passed to an up command.
$env:MVP_DB_PASSWORD = 'validation-only'
$env:MVP_RABBIT_PASSWORD = 'validation-only'
docker compose -p live-jvg-dev1-isolated --profile integration-prepared config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Invalid Compose configuration' }
