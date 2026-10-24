$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compose = Join-Path $repoRoot 'infra/docker-compose.yml'

docker compose --env-file (Join-Path $repoRoot '.env') -f $compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Base Compose configuration is invalid.' }
docker compose --env-file (Join-Path $repoRoot '.env') -f $compose --profile monitoring config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Monitoring Compose configuration is invalid.' }

python (Join-Path $PSScriptRoot 'validate-observability.py')
if ($LASTEXITCODE -ne 0) { throw 'Observability static checks failed.' }

$infraPath = (Join-Path $repoRoot 'infra').Replace('\', '/')
docker run --rm --entrypoint=/bin/promtool -v "${infraPath}:/etc/prometheus:ro" prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
if ($LASTEXITCODE -ne 0) { throw 'Prometheus configuration validation failed.' }
