$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose = Join-Path $PSScriptRoot 'live-test-compose.yml'
$projectFile = Join-Path $PSScriptRoot '.live-test-project'
if (Test-Path -LiteralPath $projectFile) { throw "An integration project is already recorded. Inspect it, then use live-test-down.ps1." }
$project = 'md-it-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
Set-Content -LiteralPath $projectFile -Value $project -NoNewline
try {
  docker compose --project-directory $repo --project-name $project -f $compose up -d
  if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed.' }
  $postgres = "$project-postgres-1"
  $redis = "$project-redis-test-1"
  $primary = "$project-silo-primary-1"
  $secondary = "$project-silo-secondary-1"
  $ready = $false
  for ($i = 0; $i -lt 40; $i++) {
    docker exec $postgres pg_isready -U md_it -d minidrive_it *> $null
    $pgReady = $LASTEXITCODE -eq 0
    docker exec $redis redis-cli ping *> $null
    $redisReady = $LASTEXITCODE -eq 0
    docker exec $primary curl -fsS http://localhost:9000/minio/health/live *> $null
    $primaryReady = $LASTEXITCODE -eq 0
    docker exec $secondary curl -fsS http://localhost:9000/minio/health/live *> $null
    $secondaryReady = $LASTEXITCODE -eq 0
    if ($pgReady -and $redisReady -and $primaryReady -and $secondaryReady) { $ready = $true; break }
    Start-Sleep -Seconds 2
  }
  if (!$ready) { throw 'Disposable PostgreSQL/Silo health checks did not become ready.' }
  docker compose --project-directory $repo --project-name $project -f $compose ps
  Write-Host "Project: $project"
  Write-Host 'REDIS_HOST=127.0.0.1; REDIS_PORT=56439 (disposable, no data volume)'
  Write-Host 'DB_URL=jdbc:postgresql://127.0.0.1:55439/minidrive_it'
  Write-Host 'DB_USERNAME=md_it; DB_PASSWORD=md_it_2026_disposable'
  Write-Host 'MINIO_ENDPOINT=http://127.0.0.1:9200; MINIO_ACCESS_KEY=md_it_silo; MINIO_SECRET_KEY=md_it_silo_2026_disposable; MINIO_BUCKET=minidrive-it-primary'
  Write-Host 'MINIO_SECONDARY_ENDPOINT=http://127.0.0.1:9300; MINIO_SECONDARY_ACCESS_KEY=md_it_silo; MINIO_SECONDARY_SECRET_KEY=md_it_silo_2026_disposable; MINIO_SECONDARY_BUCKET=minidrive-it-secondary'
} catch {
  docker compose --project-directory $repo --project-name $project -f $compose down --volumes --remove-orphans
  Remove-Item -LiteralPath $projectFile -Force -ErrorAction SilentlyContinue
  throw
}
