# MiniDrive Phase 13 — Final Engineering Report

**Recorded:** 2026-10-22  
**Branch / measured revision:** `main` / `2582085b3d638115ab3366c029a59f6b4dd110f3`  
**Final verdict:** **PASS — Phase 13 analysis and reporting complete; no speculative performance change made.** This is a local-machine result, not a production capacity claim or service-level objective. The host reached near-saturation at the highest tested profile, and performance beyond that point is not established.

## 1. Executive summary

Four counted successful k6 workloads completed with exit code 0, zero failed HTTP requests, zero failed checks, and expected status distributions. Request totals reconcile to eight requests per workflow iteration. The sustained 2-VU, 5-VU plateau, and 10-VU plateau profiles provide usable client-side comparisons. The one-iteration smoke run is included for completeness only and is not statistically compared to sustained workloads. Three preliminary smoke attempts remain explicitly inconclusive and excluded.

From 5 to 10 VUs, measured throughput increased from 100.52 to 112.13 requests/s (**+11.6%**); p50 increased **41.4%**, p95 **56.0%**, and p99 **69.2%**. Host CPU samples rose from a peak of 64.8% to 97.9%, while free host memory fell from 2,042 to 1,910 MiB. The backend process CPU metric peaked at about 8.6% and 13.0% of one normalized CPU, respectively. This supports host capacity pressure as the leading hypothesis, but does not prove that the application, rather than another host process or shared dependency, caused saturation.

No application-level bottleneck is demonstrated strongly enough to justify a performance code change. The available API and storage timers expose operation counts and duration sums, but not compatible histogram buckets; PostgreSQL, Redis, and storage-node CPU/memory and PostgreSQL query timing are unavailable. A controlled host/process/container measurement experiment is the next step before tuning.

## 2. Scope and test environment

- Target was restricted by the harness to `http://127.0.0.1:8080` on the local Windows/amd64 machine.
- k6 was `v2.2.0` (`00a9a1b7f5`, Go 1.26.5); backend revision and branch are recorded above.
- Each workflow used a 65,536-byte fixture and performed authenticated identity lookup, file listing, upload, download with byte-for-byte content validation, and exact-ID deletion.
- Effective storage was primary-only: primary enabled, secondary disabled, replication disabled. This benchmark says nothing about replication performance.
- Successful run metadata, scenario config, summaries, Prometheus captures, and cleanup records are in the four run directories listed in [Artifact index](#13-reproducible-commands-and-artifact-index). The original [Prompt 2 report](REPORT.md) is preserved.

## 3. Observability implementation and runtime validation

Prompt 1 observability changes already in the working tree provide bounded-label Micrometer timers and counters for file operations, routed storage-node operations and bytes, storage-node health, and resumable-upload-session work. Timer durations are seconds and byte totals are bytes. Labels avoid request, file, user, session, URL, and exception values. Storage download timing/byte counting follows the consumed stream through EOF or close.

During all counted runs, captured `up{job="minidrive"}` was 1, the backend health check reported UP, and `/actuator/prometheus` was available. HTTP request rates and custom file/storage operation and byte rates were present in each during capture. Upload-session metrics correctly had no samples because the tested single-shot workflow does not create resumable sessions. Configured primary storage health and operation counters were present; the secondary node being healthy does not mean it participated in this primary-only benchmark.

The request timer captures count and sum, not a distribution histogram. Neither the saved Prometheus captures nor dashboard queries provide `http_server_requests_seconds_bucket`; therefore server-side p50/p95/p99 cannot be calculated from these artifacts. Do not infer percentiles from count and duration sum.

## 4. Prometheus and Grafana verification

`infra/prometheus.yml` scrapes `/actuator/prometheus` on `127.0.0.1:8080` every 15 seconds. Saved pre/during/post query captures show the target up and the expected JVM, normalized backend process CPU, HTTP count rates, custom file/storage operation rates, and storage byte rates. Captures were taken during meaningful portions of the sustained profiles, not at a fine enough cadence to resolve individual latency spikes. Baseline has four during captures, ramp seven, and ramp10 nine; the recorded k6 durations were about 60 seconds, 120 seconds, and 160 seconds respectively. The captures do not cover every second, and are sparse samples rather than continuous host traces.

Grafana provisioning references Prometheus and the saved overview dashboard provides request-rate, status-rate, JVM, process CPU, custom-operation, and storage panels. Configuration and dashboard files were inspected locally. There is no saved browser/UI session proving a rendered Grafana view; runtime verification here is Prometheus readiness and the captured metrics queries. No Prometheus histogram quantile panel is used.

## 5. Test scenarios and effective VU progression

All sustained workloads used `http_req_failed rate < 0.05` and `checks rate > 0.95`; the summaries record both thresholds as passed. The ramp evidence records one-second sampled VU progression and confirms each intended plateau was reached and held for 59 sampled seconds. Baseline used a constant 2 VUs for 60 seconds. Ramp increased through 1, 2, 3, 4, 5 VUs and held at 5; ramp10 increased through 1–10 VUs and held at 10. These are observed VU values, not merely configured maxima.

## 6. Consolidated performance comparison

| Profile | Effective load | Iterations | Requests | Requests/s | HTTP p50 / p95 / p99 (ms) | Upload p50 / p95 / p99 (ms) | Download p50 / p95 / p99 (ms) | Host CPU peak | Minimum free host memory | Peak JVM heap | HTTP failures / failed checks |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Smoke | 1 VU, 1 iteration, 0.41 s | 1 | 8 | 19.38 | 29.06 / 97.57 / 108.21 | 72.87 / 72.87 / 72.87 | 20.91 / 20.91 / 20.91 | 40.0% | 2,947 MiB | 52.4 MiB | 0 / 0 |
| Baseline | 2 VUs, 60 s | 801 | 4,008 | 66.51 | 14.68 / 38.62 / 46.69 | 34.60 / 46.53 / 62.54 | 12.63 / 19.09 / 23.05 | 64.9% | 2,720 MiB | 60.8 MiB | 0 / 0 |
| Ramp | 1→2→3→4→5; 5-VU plateau, 59 sampled seconds | 2,418 | 12,093 | 100.52 | 15.81 / 43.44 / 52.23 | 38.49 / 52.05 / 59.63 | 14.28 / 19.65 / 23.70 | 64.8% | 2,042 MiB | 54.5 MiB | 0 / 0 |
| Ramp10 | 1→2→…→10; 10-VU plateau, 59 sampled seconds | 3,596 | 17,983 | 112.13 | 22.36 / 67.74 / 88.38 | 58.47 / 87.38 / 112.45 | 20.97 / 34.63 / 45.18 | 97.9% | 1,910 MiB | 69.2 MiB | 0 / 0 |

The supplied host/memory figures in this table are the aggregate values retained in each `result.json` and the existing Prompt 2 report. Independently summarized values from only `prometheus-during-*.json` samples can differ because those are sparse windows: e.g. baseline during-only host free memory bottoms at 3,058 MiB and the sampled host CPU tops at 54.4%, while its aggregate result records 2,720 MiB and 64.9%. This discrepancy is retained, not silently reconciled away. The report’s aggregate host figures include a broader sample set than the during-only files available for this recalculation; exact sample-level provenance is not fully reconstructable from the sanitized captures. JVM aggregate peaks have the same issue: during-only sampled heap maxima are lower than the reported aggregate peak.

### Count reconciliation

For each workflow iteration, k6 issued five API requests (identity, list, upload, download, delete), plus three setup requests (health, Prometheus, login) once per process. Thus request count is `5 × iterations + 3`: 8, 4,008, 12,093, and 17,983. Per-run response totals reconcile exactly: smoke 200×6, 201×1, 204×1; baseline 200×2,406, 201×801, 204×801; ramp 200×7,257, 201×2,418, 204×2,418; ramp10 200×10,791, 201×3,596, 204×3,596. The three setup requests are included in HTTP requests/status counts, but the loop check pass counts reflect seven checks per iteration plus three setup checks: 10, 5,610, 16,929, and 25,175 passes respectively. All failed-check counts are zero. The prior run report’s successful-profile table is consistent with the source summaries.

## 7. Throughput and latency interpretation

Baseline to the 5-VU ramp plateau increased request rate **51.1%** (66.51→100.52/s), with p50 **+7.7%**, p95 **+12.5%**, and p99 **+11.9%**. The controlled increase delivered substantially more throughput with moderate tail growth on this host.

From 5 to 10 VUs, request rate increased only **11.6%** (100.52→112.13/s), despite doubling plateau VUs. Iterations rose 48.7% because the ramp10 profile has more active VUs over its full ramp-and-hold duration; iterations/s and requests/s are the fairer rate comparisons. HTTP p50 rose **41.4%** (15.81→22.36 ms), p95 **56.0%** (43.44→67.74 ms), and p99 **69.2%** (52.23→88.38 ms). This is a clear diminishing-return / queueing-pressure signature, coincident with host CPU rising to 97.9%. It is consistent with saturation but does not identify which process or shared resource is causal.

The smoke result is one sample per custom operation and eight HTTP requests in 0.41 seconds. Its percentiles are degenerate single-observation values and its setup/short-run effects dominate; comparisons with sustained runs are not statistically meaningful.

k6 `http_req_duration` is client-observed request duration, including network/request processing and response transfer. Its waiting component (time to first byte after sending) and receiving component are separately present in the raw k6 summary. The server-side Micrometer HTTP timer is a different measurement boundary. Prometheus captures expose rates/counts and cumulative duration, not valid percentile distributions; direct client/server percentile equivalence cannot be claimed.

## 8. Upload and download analysis

At 2 VUs, upload p95/p99 were 46.53/62.54 ms versus download 19.09/23.05 ms. At 5 VUs they were 52.05/59.63 ms versus 19.65/23.70 ms. At 10 VUs upload rose to 87.38/112.45 ms and download to 34.63/45.18 ms. From 5 to 10 VUs, upload p95/p99 increased **67.9% / 88.6%** and download p95/p99 increased **76.3% / 90.6%**. Their p50 values rose 51.9% and 46.8% respectively.

These custom k6 trends are end-to-end request durations for upload and download. The workload transfers 64 KiB each way, verifies downloaded content, and deletes each fixture. Both transfer paths degrade at high load; the observations do not isolate storage time from multipart parsing, application work, HTTP transfer, host scheduling, or shared dependencies. During Prometheus samples show primary-node operation rates and byte throughput roughly tracking file operations, but rate/count-only captures do not establish storage operation latency percentiles or prove storage is fast. The app instrumentation wraps routed storage calls, so those timer series can be used in a future denser diagnostic capture.

## 9. Bottleneck investigation

### Host and JVM

The saved aggregate result reports host CPU peaks 64.9%, 64.8%, and 97.9% for baseline, ramp, and ramp10; minimum host free memory 2,720, 2,042, and 1,910 MiB; JVM heap peaks 60.8, 54.5, and 69.2 MiB. These are host versus JVM distinctions: host CPU/free memory describe the machine sampling source; `process_cpu_usage` and `jvm_memory_used_bytes` describe the backend process/JVM. During-only samples show backend process CPU maxima approximately 0.080, 0.086, and 0.130 normalized CPU, and heap maxima approximately 58.0, 51.7, and 66.0 MiB. Those modest JVM samples alongside high host CPU weaken (but do not eliminate) the hypothesis that Java CPU alone consumed the host. The identity and CPU use of other host processes and per-core scheduling/steal data are missing.

Prometheus host samples are sparse (4/7/9 during captures), so they establish sampled peaks rather than continuous maxima. There are no per-container CPU/memory samples for dependencies. The host memory and CPU summary discrepancy described above limits precise sample-level correlation. No new 10-VU run was justified: the existing run already shows CPU near saturation and sharply worsening tails.

### Backend and API

During sampled HTTP request rates broadly match the test workload’s expected path distribution, and custom file-operation and routed storage-operation counts track upload/download/delete calls. No HTTP failures, check failures, unhealthy dependency samples, or backend health regression were observed. The available artifacts do not include per-route latency quantiles. They therefore do not support a defensible ranking of slowest server routes. k6 can rank its own measured request groups only where per-request tags/raw outputs are retained; the sanitized result is aggregate and raw NDJSON is outside Git.

Measured methods use streamed storage downloads and multipart upload. No code-level inefficiency on these measured paths is proven by the available timing evidence. Authentication, metadata persistence, object-store calls, response streaming, and host contention are all candidates, but none is isolated sufficiently to warrant altering behavior.

### PostgreSQL

PostgreSQL CPU, memory, and detailed query timings were not captured. Container health checks only show dependency health, not low latency. No saved `pg_stat_statements` evidence or safe live database statistics snapshot exists. Code inspection indicates the exercised workflow performs user lookup for authentication, file listing by owner, metadata creation/cleanup, and associated entity persistence/deletion; the benchmark artifact set cannot establish whether any of those queries is slow. No `EXPLAIN ANALYZE` was run against an environment without captured workload isolation. A query-level bottleneck is therefore unproven.

### Redis and storage

Redis CPU and memory were unavailable, and the current measured workflow does not generate Redis-specific timing in saved artifacts. Storage-node CPU/memory were unavailable. Primary storage health and routed operation/byte counters were visible, and only the primary node participated. The storage timer’s aggregate count/sum can quantify total recorded call duration if captured at matching pre/post points, but no histogram bucket series or request-correlated sample provides a defensible p95/p99 storage latency. Healthy container status is not evidence of low storage latency. Replication-specific behavior is outside this benchmark.

### Measurement quality and metric labels

Prometheus used 15-second scrape/evaluation intervals. During query snapshots span the bulk of each profile but are sparse and include rate windows (`[1m]`) that smooth ramp transitions. They can validate availability and broad rate/resource behavior, not moment-by-moment latency causality. Custom labels are bounded (`operation`, `outcome`, configured `node_id`); HTTP route labels use normalized URI templates in saved series, avoiding file-ID cardinality. The metrics expose no histogram buckets in the saved captures; no `histogram_quantile` calculation was applied.

## 10. Optimization decision

No performance optimization was applied. Evidence ranks the hypotheses as follows:

1. **Shared host CPU capacity pressure — high confidence that pressure occurred; medium confidence as cause of tail growth.** Host sampled CPU reached 97.9% at 10 VUs as throughput flattened and tails rose. Causation is not proven because other host processes and per-container usage were not measured.
2. **Shared dependency/storage or host scheduling contribution — plausible, low-to-medium confidence.** Upload/download tails both increased and primary storage rates tracked calls, but no storage, DB, Redis resource metrics or latency percentiles are available.
3. **Specific backend API/JVM code bottleneck — low confidence.** Backend process CPU and heap were modest in sampled captures; no server-side route percentiles or route-level profiles are available.
4. **Specific PostgreSQL query bottleneck — unproven.** No query statistics or timings were collected.

Changing concurrency, transaction behavior, integrity checks, permission checks, streaming, or replication would be speculative and could weaken correctness or security. The next useful experiment is a repeated controlled 2/5/10-VU profile on a quiet host with synchronized per-process/container CPU and memory (including the load generator), process CPU/GC/thread data, PostgreSQL query statistics, Redis/container telemetry, and denser or histogram-enabled route and storage timing. First confirm host headroom; no higher-load run is recommended on the currently evidenced machine.

## 11. Failed/inconclusive attempts and cleanup reconciliation

Three preliminary smoke attempts have incomplete execution evidence due to PowerShell process-output handling and remain excluded from performance comparisons. `smoke-20261022T112900Z` has fixture creation and exact-ID cleanup evidence (HTTP 204). `smoke-20261022T113100Z` left one fixture; `reconciliation-smoke-20261022T113100Z.json` records deletion of only its exact ID with HTTP 204. `smoke-20261022T113300Z` records one successful file-delete check and no attributable post-run inventory delta, but retained no exact fixture ID; it remains inconclusive. The successful one-iteration smoke is `smoke-20261022T113600Z` and is separate from those attempts.

The four counted runs created 6,816 fixtures and recorded 6,816 exact-ID HTTP 204 deletions, with zero unresolved fixtures. Baseline’s immediate inventory artifact included the earlier interrupted smoke fixture because reconciliation took place afterward; its post-reconciliation inventory artifact records the return to the pre-test state. The original `testfile.bin` (`28b2f2b8-071c-4287-be60-9a7b39c4f4d1`) remains untouched. No user fixture was deleted.

## 12. Missing telemetry and limitations

- PostgreSQL CPU/memory and query-level timing: unavailable.
- Redis CPU/memory and workload-specific command timing: unavailable.
- Storage-node CPU/memory: unavailable. Storage call count/bytes are present, but compatible histogram buckets and reliable operation percentiles are not.
- Host CPU/memory summary cannot be reconstructed exactly from the retained during-only captures; report aggregate values are preserved alongside differing during-only observations.
- Host process attribution, load-generator resource use, per-core CPU saturation, GC pause/time series, thread pool/connection pool saturation, disk I/O, network utilization, and container limits were not recorded.
- Sparse 15-second Prometheus captures and one-minute rate windows cannot establish precise correlation or transient maxima.
- Preliminary smoke evidence limitations are retained; these runs are not passes and not included in comparisons.
- Results represent one local environment, one 64 KiB workflow, and primary-only storage. They define no production capacity or SLO.

## 13. Reproducible commands and artifact index

The original Prompt 2 harness is `infra/load-testing/minidrive.js`. From PowerShell, with the local backend/services running and the dedicated test account supplied as environment variables, representative commands are:

```powershell
$env:K6_EMAIL = '<dedicated-test-account>'
$env:K6_PASSWORD = '<local-secret>'
$env:PROFILE = 'baseline'
$env:VUS = '2'
$env:DURATION = '60s'
k6 run infra/load-testing/minidrive.js
```

The recorded ramp profiles used the same script with `PROFILE=ramp` and `PROFILE=ramp10`; their stage and plateau defaults are defined in the harness. Do not rerun ramp10 on a saturated machine solely to reproduce this report. Raw k6 NDJSON and console logs were stored under `%TEMP%\minidrive-phase13\` and are intentionally outside the repository.

Artifact index:

- [Prompt 2 report](REPORT.md) — original controlled-load report, preserved.
- `preflight-20261022T112753Z/` — initial configuration and account inventory evidence.
- `smoke-20261022T113600Z/` — the one successful, one-iteration smoke; exclude its latency from sustained comparisons.
- `baseline-20261022T114000Z/` — 2-VU run, summary/result, Prometheus captures, execution and cleanup records, plus post-reconciliation inventory.
- `ramp-20261022T114000Z/` — 5-VU plateau, VU progression, summaries, Prometheus captures, cleanup evidence.
- `ramp10-20261022T114000Z/` — 10-VU plateau, VU progression, summaries, Prometheus captures, cleanup evidence.
- `smoke-20261022T112900Z/`, `smoke-20261022T113100Z/`, `smoke-20261022T113300Z/` — preliminary inconclusive attempts, preserved and excluded.
- `reconciliation-smoke-20261022T113100Z.json` — exact-ID cleanup of interrupted smoke fixture.

For each successful profile, `summary.json` holds raw k6 summary metrics; `result.json` is the sanitized derived summary; `metadata.json` records environment and scenario; `prometheus-pre.json`, `prometheus-during*.json`, and `prometheus-post.json` retain monitoring observations; `cleanup.json` retains fixture deletion records; ramp profiles additionally include `vu-progression.json` and account inventories.

## 14. Requirement assessment

| Requirement | Status | Evidence / reason |
|---|---|---|
| Preserve prior Prompt 1/2 work and evidence; inspect branch/diff | **PASS** | Existing uncommitted changes and `artifacts/phase13/REPORT.md` preserved; branch/revision recorded. |
| Reconcile successful-run k6 counts, statuses, checks, thresholds | **PASS** | Per-run raw summaries and result artifacts; request formula and status distributions reconcile exactly. |
| Reconcile effective VU progression | **PASS** | Ramp/ramp10 sampled progression records show intended plateaus reached and held 59 samples. |
| Assess throughput, latency, transfer trends, failures and resource behavior | **PASS** | Consolidated table and calculated deltas; smoke handled separately; aggregate-vs-sparse sample discrepancy disclosed. |
| Validate runtime observability, Prometheus and Grafana configuration | **PASS** | Target/health/runtime metric captures and local Grafana provisioning/dashboard inspection; no saved rendered UI session. |
| Investigate host/JVM/backend/database/Redis/storage bottlenecks without unsupported claims | **PASS** | Findings ranked by evidence and confidence; missing PostgreSQL, Redis and storage-node CPU/memory/query telemetry stated explicitly. |
| Make only justified optimizations; verify any code change | **PASS** | No code changes made because no specific application bottleneck was established; no extra test or load run needed. |
| Preserve inconclusive attempts and reconcile test fixtures | **PASS** | Attempts excluded; exact-ID reconciliation recorded; 6,816/6,816 successful fixture cleanup; original test file retained. |
| Add final report and validate repository/evidence hygiene | **PASS** | This report added; final diff/status, whitespace, original fixture, and secret scans recorded in handoff. |
| Establish production capacity/SLO | **BLOCKED / OUT OF SCOPE** | Local runs do not establish either; no production claim or SLO is made. |

## 15. Final Phase 13 verdict

**Phase 13 is complete for its local observability, controlled load-test, evidence analysis, and engineering-reporting scope.** The measured 10-VU profile is a local host saturation boundary: throughput rose modestly while tail latency grew substantially. The evidence does not prove a specific application, PostgreSQL, Redis, or storage bottleneck, so optimization was not justified. Production capacity remains unestablished pending better-attributed telemetry and a suitably provisioned test environment.


