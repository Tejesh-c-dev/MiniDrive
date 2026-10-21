# MiniDrive — Phase 12.7 Final Evidence Audit and Engineering Report

**Review status:** Evidence consolidation draft; source-artifact audit is incomplete in this review environment.  
**Workloads/tests rerun during this review:** None.  
**Application/infrastructure changes during this review:** None.

## 1. Executive summary

The evidence reported in the project conversation supports PASS outcomes for the Phase 12.7 interrupted-upload integrity scenario, k6 smoke workload, and k6 baseline workload. Phase 12.6's controlled-ramp report is reported to exist and must remain attributed to Phase 12.6.

The original repository artifact directories on `C:\MiniDrive` were not mounted or available to inspect during this review. As a result, this report preserves the supplied measurements but does not claim that the Phase 12.6 ramp, monitoring correlation, or controlled-failure results have been independently reconciled against their original reports. Those evidence items are marked accordingly below. This is a limitation of this review, not a claim that the tests were not performed.

## 2. Environment and evidence scope

Reported environment/configuration:

- Backend: Spring Boot available at `http://127.0.0.1:8080` during the performance audit.
- Load generator: k6 v2.2.0 (`windows/amd64`) for the fresh smoke and baseline reported in the conversation.
- Storage/infrastructure: Docker Compose project `infra`, including PostgreSQL, Redis, primary Silo storage and secondary Silo storage.
- Prometheus endpoint on the application: `/actuator/prometheus`.
- Repository commit reported during the k6 audit: `31c3cda9b332340f07b817878205c9d98985ac69`.

The details above are reproduced from audit results supplied in the conversation; repository files and runtime state were not independently queried by this report-writing environment.

## 3. Phase 12.6 — Controlled-ramp results (prior-phase evidence)

The user reports that the controlled-ramp results and report were submitted in Phase 12.6. They must be reused as Phase 12.6 evidence and must not be described as a new Phase 12.7 workload.

**Verification status in this review: BLOCKED — source report/raw outputs were not available in the connected file workspace.** Therefore the exact run identifier, ramp stages, timestamps, throughput, latency percentiles, HTTP failures, threshold outcomes, exit code and checksums are not reproduced here. The authoritative Phase 12.6 report and saved outputs should be referenced from the local repository's `artifacts/phase12.6/` directory after source-level reconciliation.

No ramp was rerun for this report.

## 4. Phase 12.7 — Smoke test

**Reported verdict: PASS.** The reported evidence for the fresh smoke run is:

- Run directory: `artifacts/phase12.7/performance/smoke-20261021T164439Z-7e8b445a/`
- Report: `report.md`
- Updated report checksum manifest: `sha256-updated-20261021.txt`
- Workload: 1 VU, 1 iteration, 65,536-byte fixture, 2-minute maximum duration.
- HTTP requests: 8.
- Checks: 10/10 passed.
- `http_req_failed`: 0.
- `checks`: 1.
- Process exit code: 0.

The reported threshold discrepancy was resolved as a misinterpretation of the legacy k6 `--summary-export` schema. In that schema, a direct boolean `false` means the threshold was not exceeded. The reported metric values, stdout and process exit code were consistent with a passing run.

The raw `summary.json` is reported to contain `setup_data.token`. It must be treated as sensitive and excluded from shared reports, screenshots, logs and evidence indexes. The user reported that raw artifacts were preserved and that the updated manifest was generated because `report.md` changed. The raw manifest contents and checksum values were not available for independent verification in this review.

## 5. Phase 12.7 — Baseline benchmark

**Reported verdict: PASS.** The supplied baseline results are:

| Metric | Reported result |
|---|---:|
| Iterations | 1,276 |
| HTTP requests | 6,383 |
| Throughput | 105.80 requests/second |
| HTTP failure rate | 0.000% |
| Checks | 8,935 / 8,935 passed (100%) |
| p50 HTTP request latency | 13.895 ms |
| p90 HTTP request latency | 38.134 ms |
| p95 HTTP request latency | 41.258 ms |
| p99 HTTP request latency | 50.543 ms |
| HTTP 200 responses | 3,831 |
| HTTP 201 responses | 1,276 |
| HTTP 204 responses | 1,276 |
| Process exit code | 0 |

Reported evidence location:

`artifacts/phase12.7/performance/baseline-20261021T165438Z-8ff31a2e/REPORT.md`

The run report is said to include upload/download measurements, threshold reconciliation and supporting evidence. Exact upload/download metric values and the raw checksum values are not repeated here because the source report and manifest were unavailable for inspection. The reported NDJSON and summary counts agreed. All 1,276 created fixtures were reportedly deleted by exact ID with HTTP 204, and the post-run inventory matched the preflight inventory.

These are results reported by the test operator; this review did not independently recalculate them from raw artifacts.

## 6. Phase 12.7 — Interrupted-upload integrity

**Reported verdict: PASS.** The supplied test outcome is:

- Fixture size: 5,243,003 bytes.
- At interruption, the original session was `INITIATED` and only chunk `0` was recorded.
- A second client process resumed that same session, uploaded the 123-byte remainder and completed it.
- Downloaded SHA-256 matched the original: `8b64de48d04c9850121882be0a70525647e957957b3372b5c94068f6b9b5ddbe`.
- One logical file and one version were reported.
- The isolated bucket contained one final object and no staging objects before cleanup.
- The file was deleted by exact ID; the disposable Compose project and its fresh volumes were removed.
- The audited `infra` containers were reported to retain their prior IDs and states.
- No tracked application files were changed for this test.

Reported source: `artifacts/phase12.7.2a/REPORT.md`. The report and request/fixture/download evidence were described as present in that directory. Their bytes and checksums were not accessible in this review environment.

## 7. Monitoring correlation

**Evidence-review status: BLOCKED — underlying Phase 12.7 monitoring outputs were not available to inspect here.**

Earlier supplied audit findings reported that `/actuator/prometheus` returned HTTP 200 and exposed metrics including:

- `http_server_requests_seconds_count`, `_sum` and `_max`, tagged by method, URI and status.
- `jvm_memory_used_bytes` and `process_cpu_usage`.
- `minidrive_file_operation_seconds` count/sum/max, tagged by operation and outcome.
- `minidrive_storage_node_operation_seconds` count/sum/max, tagged by operation, outcome and node ID.
- `minidrive_storage_node_bytes_total`.

The same earlier audit said the external Prometheus server and Grafana were unavailable at that point, so actual scraping and dashboard query validation were unverified at that time. The user subsequently stated that the remaining activities were already done, but no underlying monitoring report or measurements were supplied in the accessible chat. Therefore this report cannot certify later monitoring readiness or a successful k6-to-Prometheus/Grafana correlation.

Latency limitation: count/sum/max alone do not establish p95 or p99 server-side latency. Any final correlation report should identify the exact metric/query and collection interval used and should not infer percentiles from maxima.

## 8. Controlled failure testing and recovery

**Evidence-review status: BLOCKED — phase-specific failure-test report/raw evidence was not available to inspect here.**

The prior audit explicitly warned that the existing live primary storage node must not be stopped: the backend configuration did not establish active secondary routing, replication, automatic failover or healing. It recommended using a distinct disposable Compose project with fresh volumes and isolated data for any storage-node outage test.

The user later indicated the remaining activities had already been done. Without the corresponding test report, commands, container identities, timestamps, observed status codes and integrity checks, this review cannot independently record the exact failure-test outcome as PASS. This is not a claim that the work was not performed; it is an evidence-access limitation.

A final project report should distinguish safe error handling and recovery after service restoration from automatic failover or replica healing. Those are different capabilities.

## 9. Bottlenecks and performance interpretation

The baseline provides an observed throughput of 105.80 requests/second and p95 HTTP request latency of 41.258 ms under the reported workload. These values are workload- and environment-specific. No explicit SLO or performance target was supplied here, so the results should not be described as meeting an SLO.

No bottleneck is declared in this report because the source ramp report and monitoring time series were not available for correlation. Candidate CPU, JVM, database or storage bottlenecks should be called confirmed only if supported by synchronized metrics and workload evidence. Otherwise, they remain hypotheses.

## 10. Acceptance status

| Acceptance criterion | Status | Basis / limitation |
|---|---|---|
| Interrupted upload resumes the same session and preserves bytes | **PASS (reported)** | Exact SHA-256 and session-state result supplied; source artifact not independently inspected here |
| k6 smoke workload and thresholds | **PASS (reported)** | 8 requests, 10/10 checks, zero HTTP failures, exit 0; legacy threshold semantics reconciled in reported run |
| k6 baseline workload and thresholds | **PASS (reported)** | 6,383 requests, 105.80 req/s, zero HTTP failures, all checks pass, exit 0 |
| Phase 12.6 controlled-ramp evidence incorporated | **BLOCKED** | Report said to exist, but source report/raw results unavailable to inspect in this review |
| Prometheus scrape and Grafana correlation | **BLOCKED** | Later completion was asserted, but no underlying monitoring results were available here |
| Controlled failed-request test | **BLOCKED** | No source test report available in this review |
| Isolated storage-node availability/recovery test | **BLOCKED** | No source test report available in this review; existing primary outage remains prohibited |
| Final artifact hashes verified | **BLOCKED** | Manifest files were reported to exist, but their contents/source files were not accessible for recomputation |
| No workloads or tests rerun during this review | **PASS** | No workload/test execution was performed |

**Overall status: BLOCKED FOR INDEPENDENT EVIDENCE SIGN-OFF.** The supplied smoke, baseline and interrupted-upload results are reported PASS. The remaining status reflects that the actual phase-specific artifacts were not mounted into this review environment, not that the user failed to complete those activities.

## 11. Evidence index

The following local repository paths were provided in the conversation and should be reconciled against their actual contents before the report is used as final sign-off:

- Phase 12.6 ramp report and raw outputs: `artifacts/phase12.6/` (exact report filename/path not supplied here).
- Interrupted upload: `artifacts/phase12.7.2a/REPORT.md`.
- Smoke report: `artifacts/phase12.7/performance/smoke-20261021T164439Z-7e8b445a/report.md`.
- Updated smoke manifest: `artifacts/phase12.7/performance/smoke-20261021T164439Z-7e8b445a/sha256-updated-20261021.txt`.
- Smoke raw summary (sensitive; do not share): `artifacts/phase12.7/performance/smoke-20261021T164439Z-7e8b445a/summary.json`.
- Baseline report: `artifacts/phase12.7/performance/baseline-20261021T165438Z-8ff31a2e/REPORT.md`.
- Prior performance evidence report: `artifacts/phase12.7/performance/REPORT.md`.
- Threshold audit: `artifacts/phase12.7/performance/threshold-audit-20261021T164009Z/threshold-audit.json`.
- Monitoring and failure reports: exact paths not supplied in the accessible conversation; discover them under `artifacts/phase12.7/` in the local repository.

Original reports and checksum manifests must remain untouched. Calculate hashes only after the final local report has finished changing, and use a new manifest if checksums for this report are desired.

## 12. Final note

This document consolidates the results supplied in the conversation and explicitly records inaccessible evidence. It is not yet an independently verified sign-off of the local repository because this execution environment did not have access to `C:\MiniDrive` or the referenced artifact files. No workloads, failure tests or monitoring checks were rerun, and no application or infrastructure files were modified during this review.
