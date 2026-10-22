# MiniDrive Phase 13 — Controlled Load Test Results

Recorded 2026-10-22 on branch `main`, commit `2582085b3d638115ab3366c029a59f6b4dd110f3`, using k6 `v2.2.0` and `http://127.0.0.1:8080` only. Effective runtime storage configuration stayed primary-only: primary enabled, secondary disabled, replication disabled. The `.env` file was not edited. All successful profiles used 64 KiB files and the script thresholds `http_req_failed rate < 0.05` and `checks rate > 0.95`.

## Successful profile comparison

| Profile | Actual VUs and duration | Iterations / HTTP requests | Requests/s | HTTP failures / failed checks | HTTP latency p50 / p95 / p99 (ms) | Upload p95 / p99 (ms) | Download p95 / p99 (ms) | Exit |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Smoke | 1 VU, 1 iteration (0.41 s) | 1 / 8 | 19.38 | 0% / 0 | 29.06 / 97.57 / 108.21 | 72.87 / 72.87 | 20.91 / 20.91 | 0 |
| Baseline | 2 VUs, 60 s | 801 / 4,008 | 66.51 | 0% / 0 | 14.68 / 38.62 / 46.69 | 46.53 / 62.54 | 19.09 / 23.05 | 0 |
| Ramp | 1→2→3→4→5 VUs; 5 VUs held 59 sampled seconds | 2,418 / 12,093 | 100.52 | 0% / 0 | 15.81 / 43.44 / 52.23 | 52.05 / 59.63 | 19.65 / 23.70 | 0 |
| Ramp10 | 1→2→3→4→5→6→7→8→9→10 VUs; 10 VUs held 59 sampled seconds | 3,596 / 17,983 | 112.13 | 0% / 0 | 22.36 / 67.74 / 88.38 | 87.38 / 112.45 | 34.63 / 45.18 | 0 |

Status distribution for each successful profile was only the expected workflow statuses: smoke `200×6, 201×1, 204×1`; baseline `200×2,406, 201×801, 204×801`; ramp `200×7,257, 201×2,418, 204×2,418`; ramp10 `200×10,791, 201×3,596, 204×3,596`. Each successful run passed its configured thresholds.

## Monitoring and host observations

- `/actuator/health` reported `UP`; `/actuator/prometheus` returned HTTP 200; Prometheus readiness returned HTTP 200 and `up{job="minidrive"}` was `1` before, during, and after every successful profile.
- PostgreSQL, Redis, primary Silo, and secondary Silo containers reported healthy before and after the runs. The configured primary storage node's operation and byte metrics were available. The secondary container was healthy but disabled for this benchmark.
- During captures included JVM heap, backend process CPU, HTTP request rates, file operation rates, storage operation rates, and storage byte rates. File, storage, and upload-session metrics for resumable sessions had no samples; these workflows do not create resumable sessions. PostgreSQL/Redis CPU and memory and storage-node CPU/memory were unavailable because no exporters expose those measurements.
- Host free memory minima were 2,720 MiB (baseline), 2,042 MiB (ramp), and 1,910 MiB (ramp10). Sampled host CPU maxima were 64.9%, 64.8%, and 97.9%, respectively. Peak JVM heap samples were about 60.8 MiB, 54.5 MiB, and 69.2 MiB. The ramp10 run completed without k6 failures or health regressions, but its 97.9% host CPU sample indicates the local machine was near CPU saturation; do not extrapolate this result to a less constrained server or add higher load on this machine without reassessing capacity.
- Full pre/during/post Prometheus JSON captures are retained per run. `result.json` records available and unavailable series explicitly.

## Cleanup and integrity

The four counted successful runs created 6,816 fixtures in total; all 6,816 received exact-ID DELETE HTTP 204, with zero unresolved fixtures. The dedicated account's original `testfile.bin` (ID `28b2f2b8-071c-4287-be60-9a7b39c4f4d1`) was present before testing and remains untouched.

PowerShell process-output handling caused three preliminary smoke attempts to have incomplete execution evidence. One attempt left a fixture; reconciliation found it by its unique k6 filename and deleted only its exact ID (`13d52c95-74da-4b31-899d-5afdfaeca96c`) with HTTP 204. The account inventory then returned to the single pre-existing file. A separate preliminary attempt has a passing `file_delete` check and no residual inventory delta, but its exact fixture ID was not retained; it is marked inconclusive and excluded from the successful comparison. These attempts and their evidence are documented in their run directories.

## Artifacts

- `preflight-20261022T112753Z/preflight.json` — endpoint, dependency, account, commit, k6 version, and effective configuration checks.
- `smoke-20261022T113600Z/`, `baseline-20261022T114000Z/`, `ramp-20261022T114000Z/`, and `ramp10-20261022T114000Z/` — sanitized summaries, run metadata, result tables, exact-ID cleanup records, VU progression, account inventories, and Prometheus captures.
- `reconciliation-smoke-20261022T113100Z.json` — exact-ID cleanup of the interrupted smoke fixture.
- Raw k6 NDJSON and console logs are under `%TEMP%\minidrive-phase13\`, outside Git. The ignored credential file remains under `infra/load-testing/` and was not copied into artifacts.

The load-testing script was already present and tracked on local `main`; no repository synchronization was performed. The script now verifies downloaded fixture content as well as size and records unresolved fixture cleanup paths. Phase 12 artifacts were not modified. No commit or push was made.

## Prompt 3 handoff

Prompt 2 workloads are complete. Prompt 3 can use the four successful run directories and this comparison. If it needs PostgreSQL/Redis or storage-node resource utilization, those measurements remain unavailable and need an exporter or container-level capture; they must not be treated as zero. The ramp10 host CPU peak should be treated as a local capacity boundary.
