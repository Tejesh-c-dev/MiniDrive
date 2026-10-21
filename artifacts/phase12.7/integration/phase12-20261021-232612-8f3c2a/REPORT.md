# Phase 12 Isolated Replication Integration Report

Run ID: `phase12-20261021-232612-8f3c2a`  
Compose project: `md1272-20261021-8f3c2a`  
Run date: 2026-10-21  
Protected project: `infra` (not modified)

## Environment and isolation

The protected environment was inspected before any changes. Its Compose network,
container names, loopback ports, and named volumes were distinct from this run.
The disposable environment used:

- Network: `md1272-20261021-8f3c2a_default`
- Volumes: `md1272-20261021-8f3c2a-postgres-data`,
  `md1272-20261021-8f3c2a-primary-data`,
  `md1272-20261021-8f3c2a-secondary-data`
- Ports: PostgreSQL `55439`, primary Silo `9029/9030`, secondary Silo
  `9129/9130`, Redis `56429`, backend `18080`
- Services: PostgreSQL 16, Redis 8.2.10-bookworm, Silo
  `RELEASE.2026-09-16T00-00-00Z`, and the backend JAR run as a host process
  with only these disposable endpoints

The Compose override and redacted effective configuration are saved beside this
report. Secrets, JWTs, and authentication tokens are not included.

## Fixtures

| Fixture | Size | SHA-256 |
|---|---:|---|
| fixture-one.bin | 4,096 | `4e441a3533bb2c10cd5649981d395744213e09a336746b5a3458fee4057205ec` |
| fixture-two.bin | 6,143 | `435653d01d6ea621b374ef997b147e451465f3f4d099745e51e2a159906c865b` |

## Scenarios and results

### Healthy replication — PASS

The isolated account registration returned HTTP 201. The first upload returned
HTTP 201 and created one logical file. PostgreSQL showed primary and secondary
replica rows as `HEALTHY`, with matching sizes and checksums. Direct downloads
from both Silo services matched the fixture hash. The API download returned
HTTP 200 and the exact 4,096 bytes.

### Secondary failure handling — PASS

Only the verified disposable secondary container was stopped. The second upload
still returned HTTP 201. The primary row remained `HEALTHY`; the secondary row
was `FAILED`, retained the expected size/checksum, incremented `attempt_count`,
and received `next_attempt_at`. No write failover was claimed.

### Healing after secondary recovery — PASS

The exact secondary Compose service was restored. The scheduled repair path
changed the secondary row to `HEALTHY`; the measured observation was 11.048
seconds after the service became healthy. Direct retrieval from secondary
matched the 6,143-byte fixture hash. Prometheus recorded one successful healing
event.

### Primary-read fallback — PASS

The verified disposable primary was stopped and port `9029` was confirmed
unavailable. The API download returned HTTP 200 with the original 4,096-byte
fixture hash from the secondary. Metrics recorded:

```text
minidrive_storage_read_fallback_total{outcome="attempted"} 1
minidrive_storage_read_fallback_total{outcome="success"} 1
```

The primary was restored healthy. No write failover was claimed.

### No valid copy — PASS

Both disposable storage services were stopped. Downloading the known file
returned HTTP 502 with body `{"error":"File storage operation failed"}` rather
than a successful or corrupted response. Both services were restored healthy.

### Data integrity — PASS

Both primary and secondary direct object downloads for both fixtures matched the
original SHA-256 values and sizes. Final replica metadata contained four
`HEALTHY` rows with matching checksums.

### Existing infrastructure preservation — PASS

The `infra` container inventory before and after disposable cleanup contained the
same six IDs, names, and states. No `infra` container, volume, or network was
stopped, recreated, deleted, or modified. Disposable containers, volumes, and
network were removed by the run's project-scoped Compose command.

## Defect found and remediated

The first healthy API download returned HTTP 500 even though both objects
existed and replica rows were healthy. The preserved application exception was
`cannot execute UPDATE in a read-only transaction`: replica verification updates
metadata during `RoutingStorageService.validatedDownload`, while
`FileService.download` had been marked `@Transactional(readOnly = true)`.

The minimal fix changed only that method to a writable `@Transactional`.
`RoutingStorageReplicationTest` gained a regression assertion, and the focused
suite passed 4 tests with zero failures/errors. After rebuilding, the same API
download returned HTTP 200 with the expected hash. The initial failure evidence
and subsequent successful rerun are retained in this directory.

## Evidence index

- `compose.override.yml` and `effective-config-redacted.txt` (disposable
  definition and redacted effective configuration)
- `runtime-identities.txt`, `image-versions.txt`, `effective-config-redacted.txt`
- `fixtures.txt`, `all-copy-integrity.txt`
- `replicas-healthy.txt`, `replicas-secondary-failed.txt`,
  `replicas-after-healing.txt`, `replicas-final.txt`
- `secondary-failure-api.txt`, `healing-timing.txt`,
  `primary-fallback-integrity.txt`, `metrics-primary-fallback.txt`,
  `metrics-final.txt`, `no-valid-copy-result.txt`
- `application-events-filtered.log`
- `infra-before-cleanup.txt`, `infra-after-cleanup.txt`,
  `infra-preservation-diff.txt`

## Final verdict

| Criterion | Verdict |
|---|---|
| Healthy replication | PASS |
| Secondary failure handling | PASS |
| Healing after secondary recovery | PASS |
| Primary-read fallback | PASS |
| Explicit failure when no valid copy exists | PASS |
| Data integrity | PASS |
| Existing infrastructure preservation | PASS |

Based on this real storage integration evidence and the focused regression
rerun, Phase 12 can be closed for the replication, healing, and read-fallback
scope. The previously documented external Prometheus workload-correlation
limitation remains unchanged.
