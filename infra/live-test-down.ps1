$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose = Join-Path $PSScriptRoot 'live-test-compose.yml'
$projectFile = Join-Path $PSScriptRoot '.live-test-project'
if (!(Test-Path -LiteralPath $projectFile)) { throw 'No recorded integration project; refusing teardown.' }
$project = (Get-Content -LiteralPath $projectFile -Raw).Trim()
if ($project -notmatch '^md-it-[0-9a-f]{12}$') { throw 'Recorded project identity is invalid; refusing teardown.' }
$ids = @(docker ps -aq --filter "label=com.docker.compose.project=$project")
if ($LASTEXITCODE -ne 0) { throw 'Could not verify Docker project ownership.' }
foreach ($id in $ids) {
  $labelJson = docker inspect --format '{{json .Config.Labels}}' $id
  if ($LASTEXITCODE -ne 0) { throw "Could not inspect container $id; refusing teardown." }
  $labels = $labelJson | ConvertFrom-Json
  if ($labels.'com.docker.compose.project' -ne $project) { throw "Container $id ownership mismatch; refusing teardown." }
}
docker compose --project-directory $repo --project-name $project -f $compose down --volumes --remove-orphans
if ($LASTEXITCODE -ne 0) { throw 'Compose teardown failed; project marker retained.' }
Remove-Item -LiteralPath $projectFile -Force
Write-Host "Removed disposable project $project and its project-scoped volumes."
