# Phase 12.7.3 Baseline Performance Report

## Verdict: PASS

Run ID: `baseline-20261021T165438Z-8ff31a2e`  
Git commit: `31c3cda9b332340f07b817878205c9d98985ac69`  
Script: `C:\MiniDrive\infra\load-testing\minidrive.js`  
Executable: `C:\Users\hteju\AppData\Local\MiniDrive\tools\k6\k6.exe`  
Version: k6 v2.2.0, commit `00a9a1b7f5`, Go 1.26.5, windows/amd64  
Started: `2026-10-21T16:55:28.3426723Z` (local `2026-10-21T22:25:28.3426723+05:30`, India Standard Time)  
Finished: `2026-10-21T16:56:30.8113461Z` (local `2026-10-21T22:26:30.8113461+05:30`)  
Process exit code: **0**

## Command and effective workload

```text
"C:\Users\hteju\AppData\Local\MiniDrive\tools\k6\k6.exe" run --out "json=C:\MiniDrive\artifacts\phase12.7\performance\baseline-20261021T165438Z-8ff31a2e\metrics.ndjson" "C:\MiniDrive\infra\load-testing\minidrive.js"
```

Environment workload settings were `PROFILE=baseline`, `VUS=2`, `DURATION=60s`, `FILE_SIZE_BYTES=65536`, `MAX_HTTP_FAILURE_RATE=0.05`, and `BASE_URL=http://127.0.0.1:8080`. The script selected one constant-VU scenario: two VUs for 60 seconds. Each iteration uploaded a uniquely named 65,536-byte file, downloaded and size-checked it, then deleted it by the exact ID returned by that upload. The summary path was supplied through `SUMMARY_PATH`; the command did not use the raw `--summary-export` output that can include setup data.

## Preflight

Backend health was `UP`; `/actuator/prometheus` returned HTTP 200. PostgreSQL, Redis, and both configured storage nodes were running and healthy. The dedicated account authenticated as `k6-test@localhost.test`. Its redacted pre-run inventory contained three files. Script review confirmed unique fixture names and exact-ID deletion in `finally`. The external Prometheus server was unavailable at `127.0.0.1:9090`, so its target and server-side time series were not available. The backend exposition endpoint itself was healthy.

## Results

- Iterations: **1,276** completed, 0 interrupted.
- HTTP requests: **6,383**, **105.80 requests/second**.
- HTTP failure rate: **0 / 6,383 = 0.000%** (threshold `< 0.05`).
- Checks: **8,935 passed, 0 failed; rate 1.000** (threshold `> 0.95`).
- HTTP status distribution: **200: 3,831; 201: 1,276; 204: 1,276**.
- HTTP request latency: p50 **13.895 ms**, p90 **38.134 ms**, p95 **41.258 ms**, p99 **50.543 ms**.
- Upload request latency: avg **38.533 ms**, p50 **37.994 ms**, p90 **45.235 ms**, p95 **49.298 ms**, p99 **61.535 ms**.
- Download request latency: avg **13.206 ms**, p50 **12.468 ms**, p90 **16.408 ms**, p95 **18.514 ms**, p99 **23.852 ms**.
- Client transfer totals: `data_sent` **86,119,621 bytes** and `data_received` **87,609,849 bytes**. These are total client transfers, not fixture-size measurements. The fixture size is independently set to 65,536 bytes and the workload checks downloaded response length against that size.

The k6 summary reports native threshold objects with `ok: true`; the legacy `--summary-export` schema uses a direct boolean where `false` means not exceeded. Thus both baseline results map to legacy not-exceeded `false` and pass. The expressions and metric values are unchanged. See [threshold reconciliation](threshold-reconciliation.json). Summary request/iteration/check counts agree with independently counted NDJSON points. Stdout records the completed 1,276 iterations and k6's passing scenario mark; the process exited 0.

## Cleanup

The run created **1,276** unique fixture IDs/names. Every one has a matching exact-ID delete log with HTTP **204** in [stderr](stderr.txt); the complete mapping is in [fixtures](fixtures.json). The post-run redacted inventory contains three files and exactly matches the pre-run IDs, name hashes, and sizes in [inventory-before](inventory-before.redacted.json) and [inventory-after](inventory-after.redacted.json). No pre-existing account file was removed. Backend health remained `UP` after the run.

## Evidence and limitations

- [stdout](stdout.txt), [stderr](stderr.txt), [sanitized summary](summary.json), [NDJSON time series](metrics.ndjson), [metadata](metadata.json), [exit code](exit-code.txt), [fixture IDs and names](fixtures.json), and both redacted inventories are preserved here.
- [SHA-256 manifest](sha256.txt) covers the evidence files and this report (the manifest does not hash itself).
- Summary sanitization was exercised using a synthetic fixture containing token/password fields; those fields were removed while metric values and threshold results remained unchanged. The saved baseline summary contains no setup data. Output scans found no saved smoke token or account password in baseline evidence.
- External Prometheus server metrics were unavailable. k6 request/transfer metrics and the backend's `/actuator/prometheus` endpoint were available; server-side time series were not captured.
- The previous raw smoke summary remains token-bearing and private. Its saved token is still accepted and expires at `2026-10-22T16:44:39Z`; the application has no supported logout or JWT revocation endpoint. No password or authentication behavior was changed. An updated smoke checksum manifest records the current report and evidence without changing the original `sha256.txt`.
