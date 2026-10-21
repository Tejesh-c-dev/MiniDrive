# Phase 12.7.2C Fresh k6 Smoke Verification

## Run

- Run ID: `smoke-20261021T164439Z-7e8b445a`
- Started: `2026-10-21T16:44:39Z` (metadata retains the full local-offset timestamp)
- Git commit: `31c3cda9b332340f07b817878205c9d98985ac69`
- Script: `C:\MiniDrive\infra\load-testing\minidrive.js`
- Executable: `C:\Users\hteju\AppData\Local\MiniDrive\tools\k6\k6.exe`
- Version: k6 v2.2.0, commit `00a9a1b7f5`, Go 1.26.5, windows/amd64
- Command: `"C:\Users\hteju\AppData\Local\MiniDrive\tools\k6\k6.exe" run --summary-export "C:\MiniDrive\artifacts\phase12.7\performance\smoke-20261021T164439Z-7e8b445a\summary.json" --out "json=C:\MiniDrive\artifacts\phase12.7\performance\smoke-20261021T164439Z-7e8b445a\metrics.ndjson" "C:\MiniDrive\infra\load-testing\minidrive.js"`
- Actual process exit code: **0** (`exit-code.txt`, persisted immediately after k6 returned)

## Preflight and effective workload

Verified before the workload: backend `/actuator/health` returned `UP`; Prometheus endpoint returned HTTP 200; credentials file was readable without printing its password; login and `/api/auth/me` verified `k6-test@localhost.test`; `/api/files` returned one existing file. Its redacted baseline (ID, SHA-256 of name, size) is in `inventory-before.redacted.json`.

The script uses its smoke scenario regardless of the `VUS` and `DURATION` environment variables: 1 VU, 1 iteration, `maxDuration: 2m` (k6 reports 2m30s including graceful stop). It uploads a unique `k6-...bin` fixture of 65,536 bytes. Iteration sequence: `/api/auth/me`, `/api/files`, multipart upload, download and byte-size check, then `DELETE /api/files/{id}` in `finally`. Upload ID is read from the upload response and used for deletion. The controller’s delete endpoint returns 204. The script has checks for upload ID, generated name and size and for delete status. Configured expressions were `http_req_failed: rate<0.05` and `checks: rate>0.95`. `MAX_HTTP_FAILURE_RATE` was explicitly set to `0.05`; `SMOKE_ITERATIONS=1` and `FILE_SIZE_BYTES=65536`. `VUS=2` and `DURATION=60s` were set in the process environment but are ignored by the smoke scenario implementation.

## Measurements and reconciliation

- Requests: summary `http_reqs.count=8`; NDJSON has 8 `http_reqs` points. Summary and NDJSON agree.
- Checks: summary has 10 passes, 0 fails, rate 1.0; NDJSON has 10 check points (10 pass, 0 fail). Summary and NDJSON agree.
- `http_req_failed`: exported value **0.0**, 0 failed of 8 requests. Expression: `rate<0.05`; independent numeric comparison is **true**. k6 stdout prints the native threshold as passing. `summary.json` exports `thresholds: {"rate<0.05": false}`. This conflicts with both the value and stdout.
- `checks`: exported value **1.0**, 10 passes of 10. Expression: `rate>0.95`; independent numeric comparison is **true**. k6 stdout prints the native threshold as passing. `summary.json` exports `thresholds: {"rate>0.95": false}`. This conflicts with both the value and stdout.
- Exit code **0** agrees with stdout’s passing threshold display, but conflicts with the two false threshold results in summary JSON.

The summary JSON threshold booleans are a material discrepancy. This report records it without treating the output as a passing native threshold result or attributing it to a tooling defect. No second workload was run.

## Cleanup

The iteration’s `file_delete` check passed with HTTP 204, and the post-run account inventory exactly matches the pre-run redacted baseline (one file; same ID, name hash and size), recorded in `inventory-after.redacted.json`. The script’s `finally` cleanup uses only the ID returned by this run’s upload. No manual deletion was needed. The script does not print or export the newly created fixture’s exact ID/name, so those values cannot be independently recorded after the script deleted it; the successful in-run ID-based delete check and restored inventory are the available evidence.

## Evidence and limitations

`stdout.txt`, `stderr.txt`, `summary.json`, `metrics.ndjson`, `metadata.json`, `exit-code.txt`, and redacted inventory snapshots are preserved in this directory. `sha256.txt` records SHA-256 hashes for each evidence file and this report. Credentials and bearer tokens are not included. Historical summaries/NDJSON remain unavailable, so this result cannot reconcile historical threshold inconsistencies. The current result is not clear for baseline review until the summary threshold discrepancy is investigated.

## Final threshold interpretation (read-only review)

**Verdict: PASS.** The earlier interpretation of the two `false` values as failures was incorrect.

The exact `summary.json` schema has `metrics.http_req_failed.thresholds["rate<0.05"]` and `metrics.checks.thresholds["rate>0.95"]` as direct booleans. They are not objects with an `ok` property. This is the legacy `--summary-export` schema, evidenced by its direct boolean threshold values and legacy `root_group` / `setup_data` structure. The invocation used `--summary-export`; it did not include `--new-machine-readable-summary`. The run metadata does not record whether `K6_NEW_MACHINE_READABLE_SUMMARY` was present, so its process-environment value is unknown; however, the JSON itself establishes the format actually emitted. The [k6 v2.2.0 runtime option source](https://github.com/grafana/k6/blob/v2.2.0/internal/cmd/runtime_options.go) defines the flag default as false and reads the environment override. The [summary data reference](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/) describes the distinct machine-readable schema, whose threshold entries have an `ok` field.

For this legacy export, a threshold boolean answers whether the threshold was exceeded: `true` indicates failure, `false` indicates it was not exceeded. This interpretation is confirmed by a [k6 maintainer explanation](https://community.grafana.com/t/summary-export-command/98472). Applied to this run:

- `http_req_failed`: expression `rate<0.05`; value `0`; exported threshold value `false` means not exceeded; stdout shows ✓; exit code is 0. **Pass.**
- `checks`: expression `rate>0.95`; value `1`; exported threshold value `false` means not exceeded; stdout shows ✓; exit code is 0. **Pass.**

The metric values satisfy their configured expressions, and the output evidence is consistent. k6 documents thresholds as pass/fail criteria, with a failed threshold resulting in a non-zero exit code ([thresholds documentation](https://grafana.com/docs/k6/latest/using-k6/thresholds/)). The earlier “material discrepancy” and “not clear for baseline review” statements above are superseded by this schema-aware interpretation. No workload was executed for this review.

## Evidence handling note

The raw `summary.json` includes `setup_data.token`; the earlier statement that bearer tokens were not included was inaccurate. Treat that raw artifact as credential-bearing. It was preserved unchanged as requested. `sha256.txt` was also preserved unchanged: hashes for the raw artifacts remain valid, while its `report.md` hash refers to the report before this amendment.

