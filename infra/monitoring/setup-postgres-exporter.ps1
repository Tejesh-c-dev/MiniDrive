$ErrorActionPreference = 'Stop'

if (-not $env:POSTGRES_EXPORTER_PASSWORD) {
    throw 'Set POSTGRES_EXPORTER_PASSWORD in the root .env and this PowerShell session.'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compose = Join-Path $repoRoot 'infra/docker-compose.yml'
$sql = Join-Path $PSScriptRoot 'postgres-exporter.sql'
$composeConfig = docker compose --env-file (Join-Path $repoRoot '.env') -f $compose config --format json | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Unable to read the resolved PostgreSQL Compose settings.' }
$postgresEnvironment = $composeConfig.services.postgres.environment
$postgresUser = $postgresEnvironment.POSTGRES_USER
$postgresDatabase = $postgresEnvironment.POSTGRES_DB

Get-Content -Raw $sql | docker compose --env-file (Join-Path $repoRoot '.env') -f $compose `
    exec -T postgres psql -U $postgresUser -d $postgresDatabase -v ON_ERROR_STOP=1 `
    -v "exporter_password=$env:POSTGRES_EXPORTER_PASSWORD"
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL monitoring role setup failed.' }
