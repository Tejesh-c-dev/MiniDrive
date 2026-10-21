# MiniDrive Phase 12.7 - Performance Evidence and k6 Validation

**Status: BLOCKED before workload execution.** No smoke, baseline, or ramp workload was launched. The historical threshold inconsistency cannot be reconciled because the historical summaries and NDJSON referenced by the task are absent from the available repository artifacts. The task explicitly requires stopping before further workloads when that inconsistency cannot be resolved confidently.

## Threshold inconsistency diagnosis

The current script at commit `31c3cda9b332340f07b817878205c9d98985ac69` configures native k6 thresholds:

- `http_req_failed`: `rate<0.05` by default (strictly less than 5%; `MAX_HTTP_FAILURE_RATE` can alter this).
- `checks`: `rate>0.95` (strictly greater than 95%).

The current `minidrive.js` has no custom summary or threshold-reporting code. Its threshold definitions are passed through `options.thresholds`, so threshold results produced by this version of the script are k6-native. The repository history shows the threshold block is unchanged from commit `68e7bb6`; commit `31c3cda` only adds the separate `ramp10` profile. This rules out a threshold-expression change in the current script history, but does not establish what produced flags in the absent historical reports.

The starting brief says old aggregate HTTP-failure and check rates appeared to satisfy configured expressions while their threshold flags were false. It does not provide the exact metric values, expressions for each run, serialized threshold objects, summaries, raw NDJSON, historical script revision, or process exit codes. Therefore:

- The historical reported rates cannot be numerically compared with each run's exact strict threshold expression.
- It is unknown whether historic flags came from native k6, a wrapper, or custom report code.
- The historical executable/version and effective environment overrides are unknown.
- No reporting defect is established. No tooling change is justified from the available evidence.
- All previous results remain **historical/unverified**, not passing, because their process exit codes and source artifacts are unavailable.

The repository's `artifacts/` tree contained the preserved Phase 12.7.2A report and artifacts, but no k6 summary, NDJSON, or metrics files. This observation and the executable/configuration inventory are recorded in [threshold-audit.json](threshold-audit-20261021T164009Z/threshold-audit.json). Phase 12.7.2A was not repeated.

## Current environment and configured workloads

| Item | Verified value |
|---|---|
| Git commit | `31c3cda9b332340f07b817878205c9d98985ac69` |
| Script | `infra/load-testing/minidrive.js` |
| k6 executable | `C:\Users\hteju\AppData\Local\MiniDrive\tools\k6\k6.exe` |
| k6 version | `v2.2.0` (`00a9a1b7f5`, windows/amd64) |
| Backend URL / health | `http://127.0.0.1:8080` / `UP` at audit time |
| Environment overrides | None of the workload variables were set in the audit shell; script defaults apply unless set at invocation |
| Test credential identity | Local credential file exists and names `k6-test@localhost.test`, matching the helper's dedicated account name. Account ownership and current file inventory were not independently verified because no workload was authorized past the unresolved diagnostic gate. Password omitted. |

The script's actual defaults are:

| Profile | Effective scenario when defaults are used | Payload | Acceptance thresholds |
|---|---|---:|---|
| Smoke | 1 VU, 1 iteration, max duration 2 minutes; `SMOKE_ITERATIONS` controls iterations | 65,536 bytes | HTTP failures `< 0.05`; checks `> 0.95` |
| Baseline | 2 VUs, 60 seconds (`VUS`, `DURATION` configurable) | 65,536 bytes | Same |
| Ordinary ramp | Starts at 1 VU; targets 2, 3, then 5 for 20 seconds per stage; then holds 5 VUs for 60 seconds | 65,536 bytes | Same |

`FILE_SIZE_BYTES`, `SMOKE_ITERATIONS`, `VUS`, `DURATION`, `RAMP_STAGE_DURATION`, `RAMP_HOLD_DURATION`, and `MAX_HTTP_FAILURE_RATE` are read from the environment. In particular, VUs and duration settings do not replace the smoke scenario's hard-coded single VU and iteration count. The profile definitions above are script-derived configuration, not executed workload observations.

## Run outcomes and measurements

| Workload | Outcome | Process exit code | Requests, latency, failures, checks, upload/download |
|---|---|---:|---|
| Smoke | Not run: stopped at unresolved historical threshold inconsistency | N/A | Not measured |
| Baseline | Not run: smoke prerequisite not met | N/A | Not measured |
| Ramp | Not run: baseline prerequisite not met | N/A | Not measured |

There are no new request throughput, latency percentile, status distribution, upload/download duration, or transfer-volume measurements. No threshold flags, metric values, and process exit codes from new runs exist to compare. No upload IDs were created, so no test-data cleanup was required or performed. The current script captures an uploaded resource's returned `id` and deletes that exact ID in `finally`; this code path was inspected, not exercised.

## Blocker and next evidence needed

To resolve the inconsistency, the historical per-run summary JSON, NDJSON, exact command/effective environment, historical script revision, and actual process exit code are needed. Those records would establish the metric values, native threshold evaluation result, and possible reporting layer for each run. Until then, launching new workloads would violate the explicit stop condition, so the smoke-to-baseline-to-ramp sequence remains blocked.

No application logic, load-testing tooling, existing infra container, storage node, or volume was changed. No k6 workload was run.

## Supporting artifact hashes

SHA-256 values for the inspected inputs and audit evidence:

| File | SHA-256 |
|---|---|
| `infra/load-testing/minidrive.js` | `0a47bb7ae39d9a7a0c2efb4668198abdccd2324a8d76d3458de7efbeba1f0e3e` |
| `infra/load-testing/README.md` | `f6c320c70b590db40409792a5415aa065cf65d61dae068a5a96d3eceb99c21d3` |
| k6 executable (`C:\Users\hteju\AppData\Local\MiniDrive\tools\k6\k6.exe`) | `87dfa91bc3e47bc4bd77911d59d7ff79f25cd76fb8322c97072f08f08a0da5ed` |
| `artifacts/phase12.7/performance/threshold-audit-20261021T164009Z/threshold-audit.json` | `2eff9bf3b974ded47ae6f3c87904ba7ebf7c4e9deb4a320274b2f72db7fd2030` |
| Preserved `artifacts/phase12.7.2a/REPORT.md` | `7024762c6923af11e9b386e3cdad873cad6f72922e768714eefe517f5680ffda` |

This report was created after the listed audit evidence; its own checksum is not embedded in itself.
