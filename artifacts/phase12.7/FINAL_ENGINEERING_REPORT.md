# MiniDrive Phase 12.7 Final Engineering Report

## 1. Executive summary

This report consolidates the preserved Phase 12.6 and Phase 12.7 evidence without
rerunning workloads or tests. The available evidence supports:

- **PASS** for the Phase 12.7 smoke run, including cleanup and the corrected
  interpretation of the legacy k6 `--summary-export` threshold booleans.
- **PASS** for the Phase 12.7 baseline run, including exact-ID cleanup and
  inventory reconciliation.
- **PASS** for the Phase 12.7.2A interrupted-upload/resume and byte-integrity
  test.
- **NOT TESTED/BLOCKED** for a Phase 12.6 controlled ramp: no ramp report or
  saved ramp results were found in the repository or available session evidence.
- **NOT TESTED/BLOCKED** for Phase 12.7 Prometheus/Grafana correlation:
  the backend exposition endpoint was available, but the external Prometheus
  server at `127.0.0.1:9090` was unavailable and no Grafana measurements were
  preserved.
- **NOT TESTED** for storage-node outage, automatic failover, or healing. The
  interrupted-upload test interrupted a client process, not a storage node.

These results are engineering evidence, not an SLO declaration. No explicit
performance SLO target was established in the reviewed artifacts.

## 2. Environment and architecture

The measured k6 runs used Windows/amd64, k6 v2.2.0
(`00a9a1b7f5`), commit `31c3cda9b332340f07b817878205c9d98985ac69`, the
Spring Boot backend at `http://127.0.0.1:8080`, and the dedicated local
account `k6-test@localhost.test`. The workload used 65,536-byte fixtures and
performed authentication, file listing, upload, download/size verification, and
exact-ID deletion.

The disposable integrity run used an isolated backend on port 18087, an
isolated PostgreSQL database, Redis, and one Silo-compatible object-storage
service. Its secondary storage service was explicitly disabled for isolation.
The normal Compose architecture contains PostgreSQL, Redis, a primary Silo
node, and an optional secondary Silo node. Redis is configured as a cache, not
the source of truth.

The load-test script defines controlled smoke, baseline, `ramp`, and `ramp10`
profiles. Its failure/check thresholds are test safeguards:
`http_req_failed: rate<0.05` and `checks: rate>0.95`; they are not declared
service SLOs.

## 3. Phase 12.6 ramp evidence

### Result: NOT TESTED / BLOCKED by missing evidence

No `artifacts/phase12.6/` directory, ramp report, ramp summary, ramp NDJSON,
run metadata, or process exit-code artifact was found. Repository history and
available session evidence likewise did not provide a saved Phase 12.6 ramp
result. The load-test README describes possible `ramp` and `ramp10` profiles,
but configuration documentation is not execution evidence.

Accordingly, no ramp run identifier, timestamp, effective configuration,
throughput, latency, threshold result, cleanup result, or exit code is
attributed to Phase 12.6 in this report. The ramp is not claimed as newly
executed during Phase 12.7.

## 4. Phase 12.7 smoke

**Run:** `smoke-20261021T164439Z-7e8b445a`  
**Started:** `2026-10-21T16:44:39.2117606Z`  
**Commit:** `31c3cda9b332340f07b817878205c9d98985ac69`  
**Configuration:** 1 VU, 1 iteration, 2-minute max duration, 65,536-byte
fixture, `BASE_URL=http://127.0.0.1:8080`  
**Exit code:** 0

Measured results:

- 8 HTTP requests.
- 10 checks, 10 passing, 0 failing (`checks=1.0`).
- `http_req_failed=0.0`; 0 failed requests.
- The k6 expressions were `rate<0.05` and `rate>0.95`.
- The legacy summary exported direct `false` threshold booleans. For this
  schema, `false` means the threshold was not exceeded; it is not a failed
  `ok` field. The values, stdout, and exit code therefore support **PASS**.
- The uploaded test file was deleted by the ID returned from that upload, and
  the redacted post-run inventory matched the pre-run inventory.

The raw smoke summary contains setup data including a token. It was preserved
unchanged as sensitive evidence and is not reproduced or copied here.

## 5. Phase 12.7 baseline

**Run:** `baseline-20261021T165438Z-8ff31a2e`  
**Started:** `2026-10-21T16:55:28.3426723Z`  
**Finished:** `2026-10-21T16:56:30.8113461Z`  
**Configuration:** 2 VUs, 60 seconds, constant-VU scenario, 65,536-byte
fixture, `BASE_URL=http://127.0.0.1:8080`  
**Exit code:** 0

Measured results:

- 1,276 iterations and 6,383 HTTP requests.
- Throughput: 105.80 requests/second.
- HTTP failures: 0/6,383 (0%).
- Checks: 8,935/8,935 passing (rate 1.000).
- HTTP latency: p50 13.895 ms, p90 38.134 ms, p95 41.258 ms, p99 50.543 ms.
- Status counts: 200 = 3,831; 201 = 1,276; 204 = 1,276.
- Upload latency: average 38.533 ms, p50 37.994 ms, p95 49.298 ms,
  p99 61.535 ms.
- Download latency: average 13.206 ms, p50 12.468 ms, p95 18.514 ms,
  p99 23.852 ms.
- Client totals: 86,119,621 bytes sent and 87,609,849 bytes received. These
  are client transfer totals, not fixture-size totals.

All 1,276 created fixtures have exact-ID deletion records with HTTP 204. The
post-run redacted inventory contains the same three files as the pre-run
inventory. Backend health remained `UP`.

The run report states PostgreSQL, Redis, and both configured storage nodes were
healthy during preflight. The native threshold values were passing, and the
process exited 0.

## 6. Interrupted upload and byte integrity

**Run:** Phase 12.7.2A, session
`077d9b50-5924-4d84-9075-329668e9ed02`  
**Times:** interruption at `2026-10-21T16:23:07.6899241Z`; resume began at
`2026-10-21T16:23:58.0148801Z`

A 5,243,003-byte fixture was split into two 5,242,880-byte chunks plus a
123-byte remainder. Client process one uploaded chunk 0 and stopped. A second
client process reused the same upload session, observed chunk 0, uploaded chunk
1, and completed the session.

Evidence shows:

- Source and downloaded SHA-256:
  `8b64de48d04c9850121882be0a70525647e957957b3372b5c94068f6b9b5ddbe`.
- Source and downloaded size: 5,243,003 bytes.
- One logical file, one version (version 1), and one final object.
- Metadata checksum and downloaded checksum matched the source.
- No staging object remained after completion.
- Seven scripted assertions passed.
- Cleanup deleted only the test-owned file ID with HTTP 204; the isolated
  bucket was empty afterward.
- The disposable project and its created resources were removed, while audited
  pre-existing infrastructure container IDs and statuses were unchanged.

This is evidence of resumability and byte integrity after a client interruption.
It is not evidence of storage-node failover, replication, automatic healing, or
service restart recovery.

## 7. Monitoring correlation

### Result: BLOCKED / NOT TESTED

The smoke preflight verified the backend health endpoint and backend Prometheus
endpoint (HTTP 200). The baseline preflight likewise verified
`/actuator/prometheus` (HTTP 200). However, the external Prometheus server at
`127.0.0.1:9090` was unavailable during baseline, and no Prometheus query
responses, scrape time series, or Grafana Explore/dashboard measurements were
preserved.

Therefore, no workload-to-monitoring correlation is reported for CPU, JVM heap,
HTTP request rates, storage operation rates, upload-session events, or storage
bytes. The backend exposition endpoint being healthy does not establish that a
Prometheus server scraped or retained those measurements.

## 8. Controlled failure tests and recovery

### Result: NOT TESTED

The available controlled interruption is the Phase 12.7.2A client-process
interruption described above. No artifact documents a storage-node outage,
backend restart recovery test, Redis outage, database outage, Prometheus
outage correlation, automatic failover, or healing observation.

The disposable integrity run deliberately disabled secondary storage. It
therefore cannot be used to claim replicated availability or failover.
Successful completion after a second client process resumed an upload is not
automatic failover.

## 9. Bottlenecks supported by measurements

The baseline evidence provides a measured request-latency profile and separates
upload from download latency. Within this run, upload operations were slower
than downloads (average 38.533 ms versus 13.206 ms; p99 61.535 ms versus
23.852 ms). This identifies upload processing as the slower measured operation
in this workload, but does not isolate database, application, network, or
object-storage causality.

No server-side Prometheus time series were available to identify CPU, heap,
storage, or database saturation. No breaking-point or stress workload was
executed or found, so no capacity limit is inferred.

## 10. Architectural limitations

- No evidence establishes replication correctness, automatic failover, or
  self-healing.
- The Phase 12.7.2A environment used one active storage node and explicitly
  disabled the secondary node.
- The normal Compose file provides a secondary storage service, but its
  presence is not proof that data are replicated or that routing fails over.
- Redis is cache-only and non-persistent by configuration; it is not a
  durability mechanism.
- External Prometheus/Grafana telemetry was unavailable for the baseline
  correlation window.
- No explicit SLO target was established, so PASS below means the recorded
  test assertions and cleanup evidence passed, not that a service SLO was met.

## 11. Evidence index and checksums

The original reports and raw artifacts were preserved. Existing run manifests
remain the authoritative checksums for the files they cover; the new final
report is not inserted into an original manifest.

| Evidence | Relevant files/checksums |
|---|---|
| Interrupted upload | `artifacts/phase12.7.2a/REPORT.md`; source/download SHA-256 `8b64de48d04c9850121882be0a70525647e957957b3372b5c94068f6b9b5ddbe`; detailed evidence in `stage1-evidence.json`, `stage2-evidence.json`, `cleanup-evidence.json`, and `bucket-inventory.txt`. |
| Smoke | `artifacts/phase12.7/performance/smoke-20261021T164439Z-7e8b445a/report.md`; updated manifest `sha256-updated-20261021.txt`; raw `summary.json` is sensitive because it contains setup data. |
| Baseline | `artifacts/phase12.7/performance/baseline-20261021T165438Z-8ff31a2e/REPORT.md`; manifest `sha256.txt`; threshold interpretation `threshold-reconciliation.json`. |
| Threshold audit | `artifacts/phase12.7/performance/threshold-audit-20261021T164009Z/threshold-audit.json`. |
| Phase-level performance report | `artifacts/phase12.7/performance/REPORT.md` records the earlier pre-workload blocker and is retained as historical context; its “no workload” conclusion predates the fresh smoke and baseline artifacts. |
| Architecture/configuration | `infra/docker-compose.yml`, `backend/src/main/resources/application.yml`, and `infra/load-testing/README.md`. |

For the smoke and baseline raw evidence, use the listed manifests rather than
recreating or updating the original checksum files. The final report itself
must be hashed only after its contents are final; its checksum is intentionally
not embedded in itself.

## 12. Acceptance table

| Acceptance criterion | Status | Evidence / limitation |
|---|---|---|
| Phase 12.6 controlled ramp located and attributed correctly | NOT TESTED | No Phase 12.6 ramp report or saved results found; not rerun. |
| Phase 12.7 smoke completed successfully | PASS | 1 VU, 1 iteration, 8 requests, 10/10 checks, exit 0, cleanup reconciled. |
| Legacy smoke threshold interpretation reconciled | PASS | Direct legacy booleans `false` mean not exceeded; metric values and exit 0 agree. |
| Phase 12.7 baseline completed successfully | PASS | 1,276 iterations, 6,383 requests, 0% failures, 8,935/8,935 checks, exit 0. |
| Baseline fixture cleanup and inventory preservation | PASS | All 1,276 exact-ID deletes returned 204; inventory matched. |
| Interrupted upload resumes in same session | PASS | Same session ID resumed in a second client process; completion succeeded. |
| Byte integrity and metadata verified | PASS | Size and SHA-256 matched; one file, one version, no staging object. |
| Prometheus/Grafana workload correlation | BLOCKED | Backend exposition was available; external Prometheus unavailable and no Grafana measurements preserved. |
| Controlled storage/backend failure and recovery | NOT TESTED | No outage/failover/healing evidence; client interruption is not an infrastructure failure. |
| Replication, automatic failover, and healing demonstrated | NOT TESTED | Secondary storage was disabled in the isolated integrity run; no such test evidence exists. |
| SLO attainment | NOT TESTED | No explicit target was established in the reviewed artifacts. |

## 13. Phase 12 isolated replication integration validation

Run `phase12-20261021-232612-8f3c2a` used a separately named Compose project,
network, volumes, ports, PostgreSQL database, Redis instance, primary Silo,
secondary Silo, and backend configuration. The protected `infra` container
IDs, names, states, volumes, and network were unchanged.

The real API and storage services demonstrated healthy two-copy replication,
secondary failure handling with an authoritative primary write, scheduled
secondary healing, primary-read fallback, explicit HTTP 502 failure when both
copies were unavailable, and byte-for-byte integrity for two deterministic
fixtures. The detailed evidence and per-scenario verdicts are in
`artifacts/phase12.7/integration/phase12-20261021-232612-8f3c2a/REPORT.md`.

The first healthy download exposed a concrete defect: replica verification
updates were attempted inside `FileService.download`'s read-only transaction,
causing PostgreSQL to reject the metadata update and the API to return HTTP
500. The method was changed to a writable transaction, a focused regression
assertion was added, the focused replication suite passed 4/4, and the
affected integration scenario was repeated successfully.

### Integration verdict

| Criterion | Verdict |
|---|---|
| Healthy replication | PASS |
| Secondary failure handling | PASS |
| Healing after secondary recovery | PASS |
| Primary-read fallback | PASS |
| Explicit failure when no valid copy exists | PASS |
| Data integrity | PASS |
| Existing infrastructure preservation | PASS |

Phase 12 can be closed for the replication integration scope. The earlier
external Prometheus workload-correlation limitation remains historical and was
not represented as a passing workload-correlation result.

No application logic, thresholds, infrastructure configuration, or historical
results were changed by this audit. No workload, k6 run, failure test, or
container operation was rerun.
