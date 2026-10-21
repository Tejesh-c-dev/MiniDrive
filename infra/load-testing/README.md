# MiniDrive local load testing

This is a deliberately small, local-only k6 workload. It uses the API contracts
in `AuthController`, `FileController`, and `UploadController`. It logs in with an
existing development account, checks `/api/auth/me` and `/api/files`, uploads a
unique small file, downloads and verifies its bytes, then deletes that file by
the id returned by its own upload. It never lists files to choose something to
delete. Credentials and bearer tokens are not printed or stored in the repo.

`minidrive.js` selects exactly one scenario with `PROFILE`: `smoke` (one VU,
one iteration by default; configurable up to 10), `baseline` (2 VUs for 60s by
default), or `ramp` (1 to 2 to 3 to 5 VUs over 20s per ramp stage by default,
then a 60s hold at 5 VUs), or `ramp10` (1 to 2 to 4 to 6 to 8 to 10 VUs over
20s per ramp stage by default, then a 60s hold at 10 VUs). Each iteration
uploads a 64 KiB fixture by default; `FILE_SIZE_BYTES` is limited to 1 MiB.
Baseline concurrency is limited to 5 VUs; `ramp10` is limited to 10. The script
refuses any target other than `http://127.0.0.1:8080`, requires login
credentials, verifies local backend health and the Prometheus endpoint before
a scenario, and has provisional abort guards for HTTP failures and failed
checks. Those guards are test safeguards, not agreed performance SLOs. The
ramp profiles are controlled; there is no stress or breaking-point scenario.

## Prepare and run

Start the local development stack and backend using the normal project setup.
Confirm the backend at `127.0.0.1:8080`, PostgreSQL, Redis, and the configured
storage node are healthy before proceeding. `provision-k6-account.ps1` creates
the dedicated `k6-test@localhost.test` account through the normal registration
endpoint. It generates a random password, saves it only in the ignored
`k6-credentials.local` file, and never resets an existing account password.
It refuses every target except the local loopback backend.

From a clean PowerShell session:

```powershell
$k6 = Join-Path $env:LOCALAPPDATA 'MiniDrive\tools\k6\k6.exe'
& .\infra\load-testing\provision-k6-account.ps1
$credentials = Get-Content .\infra\load-testing\k6-credentials.local -Raw | ConvertFrom-Json
$env:K6_EMAIL = $credentials.email
$env:K6_PASSWORD = $credentials.password
$env:FILE_SIZE_BYTES = '65536'
$env:BASE_URL = 'http://127.0.0.1:8080'
$script = (Resolve-Path .\infra\load-testing\minidrive.js).Path
$runDir = Join-Path $env:TEMP ("minidrive-k6-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $runDir | Out-Null
```

Run smoke first. Its default is one iteration and it cannot be configured above
10. Inspect its exit code and summary, ensure all uploaded test files were
deleted, and recheck backend, database, cache, storage, Prometheus target and
machine load before running another profile.

```powershell
$env:PROFILE = 'smoke'
$env:SMOKE_ITERATIONS = '1'
$jsonOut = 'json=' + (Join-Path $runDir 'smoke-metrics.ndjson')
$env:SUMMARY_PATH = Join-Path $runDir 'smoke-summary.json'
& $k6 run --out $jsonOut $script
$LASTEXITCODE
```

Only after smoke succeeds and the local environment remains stable, run the
baseline. To change it, set `VUS` (1–5), `DURATION`, and `FILE_SIZE_BYTES`
(1–1048576) before invocation. Start with the defaults shown here.

```powershell
$env:PROFILE = 'baseline'
$env:VUS = '2'
$env:DURATION = '60s'
$jsonOut = 'json=' + (Join-Path $runDir 'baseline-metrics.ndjson')
$env:SUMMARY_PATH = Join-Path $runDir 'baseline-summary.json'
& $k6 run --out $jsonOut $script
```

The ramp is independently selectable, never run automatically by the baseline.
It gradually ramps to 5 VUs and holds there for 60 seconds by default. Set
`RAMP_STAGE_DURATION` to change each of the three ramp-up stages and
`RAMP_HOLD_DURATION` to change the plateau. Check the `vus` series in the JSON
metrics output to confirm it reaches 5 and remains there during the plateau.
Recheck dependencies and host load between profiles; stop if failures rise,
health changes, or the development machine becomes overloaded.

```powershell
$env:PROFILE = 'ramp'
$env:RAMP_STAGE_DURATION = '20s'
$env:RAMP_HOLD_DURATION = '60s'
$jsonOut = 'json=' + (Join-Path $runDir 'ramp-metrics.ndjson')
$env:SUMMARY_PATH = Join-Path $runDir 'ramp-summary.json'
& $k6 run --out $jsonOut $script
```

The separate `ramp10` profile gradually raises load from 1 to 10 VUs in five
20-second stages, then sustains 10 VUs for 60 seconds. Both ramp profiles use
`RAMP_STAGE_DURATION` for each ramp stage and `RAMP_HOLD_DURATION` for the
plateau. Inspect the `vus` series in the JSON metrics output to verify that
the target was reached and sustained.

```powershell
$env:PROFILE = 'ramp10'
$env:RAMP_STAGE_DURATION = '20s'
$env:RAMP_HOLD_DURATION = '60s'
$jsonOut = 'json=' + (Join-Path $runDir 'ramp10-metrics.ndjson')
$env:SUMMARY_PATH = Join-Path $runDir 'ramp10-summary.json'
& $k6 run --out $jsonOut $script
```

k6 summary reports request rate (`http_reqs`), completed iterations, HTTP
failure rate, checks, and request duration average, median, p90, p95, and p99.
Set `SUMMARY_PATH` for each run. The harness writes a sanitized k6 summary
there, omitting setup data and recursively filtering credential-like fields;
do not use `--summary-export`, which can include the setup bearer token.
Threshold evaluation and metric values are retained. Fixture IDs/names and
their exact deletion statuses are logged for cleanup reconciliation.
The `minidrive_response_status_total` custom counter tags returned status and
request name. `minidrive_upload_duration` and `minidrive_download_duration`
report operation latency; k6's `data_sent` and `data_received` report client
transfer volume. Keep the JSON artifacts under `$env:TEMP`, not in Git. Clear
the session credentials when finished with `Remove-Item Env:K6_EMAIL,
Env:K6_PASSWORD`.

If a run is interrupted, inspect the unique `k6-...` files in the disposable
account before cleanup. A completed iteration deletes only its own file in a
`finally` block. Do not clean up by deleting all account files. No resumable
session is created by this workload; the resumable session timer/event metrics
therefore should remain unchanged.

## Prometheus and Grafana measurements

Capture these query results immediately before and after each run (Prometheus
may need one 15-second scrape interval after the run). The queries use the
actual exported series names:

```promql
up{job="minidrive"}
jvm_memory_used_bytes{area="heap",job="minidrive"}
process_cpu_usage{job="minidrive"}
sum by (method,uri,status) (rate(http_server_requests_seconds_count{job="minidrive"}[1m]))
sum by (operation,outcome) (rate(minidrive_file_operation_seconds_count[1m]))
sum by (operation,outcome,node_id) (rate(minidrive_storage_node_operation_seconds_count[1m]))
sum by (operation,node_id) (rate(minidrive_storage_node_bytes_total[1m]))
sum by (operation,outcome) (rate(minidrive_upload_session_operation_seconds_count[1m]))
sum by (event,outcome) (rate(minidrive_upload_session_events_total[1m]))
```

For file and storage timer latency, compare the corresponding `_sum` and
`_count` rates (average duration is `rate(..._sum[1m]) /
rate(..._count[1m])`). The current custom timers do not export histogram buckets,
so they cannot provide custom-metric p90/p95/p99. k6 supplies request and
transfer latency percentiles. The HTTP server timer may expose only aggregate
summary quantiles depending on the configured Micrometer registry; use its
exported data as-is. Storage byte counters advance as downloads are consumed.

The existing dashboard is `MiniDrive Overview` (`minidrive-overview`). Check
its scrape availability, JVM heap, CPU, request rate and status panels. In
Grafana Explore, use the same PromQL above. Verify the datasource from the UI
or its authenticated health check; an unauthenticated datasource API 401 does
not indicate datasource failure. Do not change the dashboard unless a query
gap blocks this analysis.

After starting the backend, first verify `GET /actuator/health`, target
`up{job="minidrive"} == 1`, and the listed metric series. Then run smoke,
inspect cleanup and health, and only then run baseline and separately the ramp.
