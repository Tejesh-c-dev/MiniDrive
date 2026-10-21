# MiniDrive Phase 12.7.2A — interrupted upload and resume integrity

**Result: PASS.** The test ran against a disposable backend and isolated database, Redis instance, Silo instance, and bucket. No tracked application or schema files were changed. The audited `infra` Compose containers remained running with the same IDs and statuses from before disposable teardown to after it. The disposable project was removed after evidence capture.

## Isolation checks

- Inspected `infra/docker-compose.yml`. It declares fixed container names for services, host ports `55432`, `9000/9001`, `9100/9101`, and `56379`, and project-scoped named volumes. It does not declare external/shared networks. Its init job addresses only the Compose service name `minio`.
- Used a temporary override to assign unique container names and replace (not append to) the PostgreSQL, Silo, and Redis port lists. Effective Compose config showed the isolated network `md1272a-20261021-8f3c2a_default`, volumes `md1272a-20261021-8f3c2a_postgres_data` and `md1272a-20261021-8f3c2a_minio_data`, and loopback ports `55439`, `9019/9020`, and `56419`. Those ports were checked unused first.
- Started only `postgres`, `redis`, `minio`, and its bucket initializer. The secondary Silo service was not started. The backend was launched separately with explicit overrides: database `jdbc:postgresql://127.0.0.1:55439/md1272a_db`, Redis `127.0.0.1:56419`, primary storage `http://127.0.0.1:9019`, bucket `md1272a-20261021-8f3c2a-files`, and secondary storage disabled and pointed at `127.0.0.1:19009`.
- Verified backend health `UP`; the backend startup log reported the disposable JDBC URL and schema migrations against its new database. PostgreSQL reported accepting connections, Redis replied `PONG`, and the Silo health endpoint returned HTTP 200. The private bucket initializer reported successful creation.
- Recorded audited `infra` container IDs and states before teardown. They were identical after teardown. No existing container or volume was stopped, restarted, recreated, or modified.

## Test fixture

- File: `fixture.bin` (`md-phase12-7-2a-8f3c2a.bin` when uploaded)
- Size: **5,243,003 bytes** (5 MiB + 123 bytes)
- SHA-256: **`8b64de48d04c9850121882be0a70525647e957957b3372b5c94068f6b9b5ddbe`**
- The fixture is deterministic, made from a repeating 0–255 byte sequence.
- Test account: `phase1272a-20261021-8f3c2a@example.invalid`, account ID `63f9caff-1df2-4a32-a7bf-d29de167cff6`. Registration used the supported `POST /api/auth/register` flow. The returned token is redacted and was removed during cleanup.

## Request sequence and observations

All times below are UTC on 2026-10-21. Session ID throughout: `077d9b50-5924-4d84-9075-329668e9ed02`.

| Time | Request | HTTP | Observation |
|---|---|---:|---|
| 16:23:06.394 | `POST /api/auth/register` | 201 | New disposable account created; token redacted. |
| 16:23:06.984 | `POST /api/uploads` | 201 | Server chose 5,242,880-byte chunks and 2 total chunks; status `INITIATED`. |
| 16:23:07.115 | `GET /api/uploads/{sessionId}` | 200 | `INITIATED`, `uploadedChunks: []`. |
| 16:23:07.188 | `PUT /api/uploads/{sessionId}/chunks/0`, `application/octet-stream` | 200 | Accepted exactly 5,242,880 bytes; `duplicate: false`. |
| 16:23:07.690 | `GET /api/uploads/{sessionId}` | 200 | **Interruption checkpoint:** `INITIATED`, `uploadedChunks: [0]`. The first client process ended here, before any later chunk or completion request. |
| 16:23:58.015 | `GET /api/uploads/{sessionId}` in a new client process | 200 | Same session remains `INITIATED`, `uploadedChunks: [0]`. |
| 16:23:58.100 | `PUT /api/uploads/{sessionId}/chunks/1`, `application/octet-stream` | 200 | Accepted remaining 123 bytes; `duplicate: false`. |
| 16:23:58.140 | `POST /api/uploads/{sessionId}/complete` | 200 | Completed file `2e0869d7-45d1-4d12-96b1-1e10d6931a18`, size 5,243,003, version 1. |
| 16:23:58.739 | `GET /api/uploads/{sessionId}` | 200 | `COMPLETED`; uploaded chunk rows are cleaned up, so `uploadedChunks: []`. |
| 16:23:58.759 | `GET /api/files/{fileId}` | 200 | Size 5,243,003; `currentVersion: 1`; checksum equals fixture SHA-256. |
| 16:23:58.793 | `GET /api/files` | 200 | Exactly one matching logical filename. |
| 16:23:58.897 | `GET /api/files/{fileId}/versions` | 200 | One version, number 1, size 5,243,003. |
| 16:23:58.916 | `GET /api/files/{fileId}/download` | 200 | Downloaded 5,243,003 bytes; SHA-256 matched the fixture. |

The real API contract observed in code and used here was: create with JSON fields `filename`, `contentType`, and `totalSizeBytes`; authenticated status GET; raw octet-stream PUT to indexed chunks; and authenticated POST to `/complete`. The service accepts at most 5 GiB and fixes chunks at 5 MiB. Registration returns an authentication token; no token or password is present in this report or the evidence logs.

## Integrity and metadata assertions

- Session was resumed in a second client process using the original session ID; no second upload session was created.
- Source and downloaded sizes were both 5,243,003 bytes; SHA-256 values matched exactly.
- Completion checksum and file metadata checksum matched the source SHA-256.
- Exactly one logical file with the fixture filename and one version (version 1) were observed.
- After completion, the isolated bucket contained exactly one object and no `_uploads/` staging object. After deleting the test file, the bucket inventory contained zero objects.
- All seven scripted assertions passed: completed session, metadata size, version 1, exactly one logical file, exactly one version, downloaded size, and downloaded hash.

## Commands and environment

The temporary Compose override and effective model are retained as artifacts. The secret-bearing Compose env file and encrypted bearer-token handoff were deleted after the test.

1. Effective configuration was reviewed with `docker compose -p md1272a-20261021-8f3c2a --env-file artifacts/phase12.7.2a/compose.env -f infra/docker-compose.yml -f artifacts/phase12.7.2a/compose.override.yml config --format json`.
2. Started only dependencies with `docker compose -p md1272a-20261021-8f3c2a --env-file artifacts/phase12.7.2a/compose.env -f infra/docker-compose.yml -f artifacts/phase12.7.2a/compose.override.yml up -d postgres redis minio minio-init`.
3. Started backend from `backend` using `.\mvnw.cmd spring-boot:run`, with `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET`, `MINIO_SECONDARY_ENDPOINT`, `MINIO_SECONDARY_ENABLED=false`, `JWT_SECRET`, and `SERVER_PORT=18087` set in its process environment. Secret values are intentionally not reproduced.
4. Ran the two client processes with `powershell.exe -NoProfile -ExecutionPolicy Bypass -File artifacts/phase12.7.2a/stage1-interrupt.ps1` and `powershell.exe -NoProfile -ExecutionPolicy Bypass -File artifacts/phase12.7.2a/stage2-resume.ps1`.
5. Inspected the bucket with `docker compose -p md1272a-20261021-8f3c2a --env-file artifacts/phase12.7.2a/compose.env -f infra/docker-compose.yml -f artifacts/phase12.7.2a/compose.override.yml run --rm --no-deps --entrypoint /bin/sh minio-init -c 'mc alias set local "http://minio:9000" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc ls --recursive "local/$MINIO_BUCKET"'` on its own network. No existing project endpoint was used.
6. Deleted only test file ID `2e0869d7-45d1-4d12-96b1-1e10d6931a18` through the API; response was HTTP 204. Stopped the separately launched backend, then ran `docker compose -p md1272a-20261021-8f3c2a --env-file artifacts/phase12.7.2a/compose.env -f infra/docker-compose.yml -f artifacts/phase12.7.2a/compose.override.yml down --volumes` after saving the evidence. The disposable project containers, network, and its two created volumes were all confirmed absent afterward.

## Cleanup and artifacts

Cleanup completed. The API deleted the exact test file ID (204); the bucket then had zero objects. Teardown removed only project `md1272a-20261021-8f3c2a`, its network, and its project-prefixed PostgreSQL and primary Silo volumes. No existing project resources were touched. The disposable account was contained in the test database and removed with that disposable DB volume.

Evidence directory: `artifacts/phase12.7.2a/`

- `REPORT.md` — this report
- `fixture.bin`, `downloaded.bin` — original and downloaded bytes for independent hashing
- `stage1-evidence.json`, `stage2-evidence.json` — timestamped redacted requests, responses, status, and assertions
- `bucket-inventory.txt`, `bucket-inventory-after-delete.txt` — isolated object inventory before and after exact file deletion
- `compose.override.yml`, `compose-model.json` — isolation override and reviewed effective network/volume/port model
- `cleanup-evidence.json`, `teardown-evidence.json`, `infra-container-state-before-down.txt` — cleanup and unchanged infra observations
- `stage1-interrupt.ps1`, `stage2-resume.ps1` — the two client-process scripts

No k6 workload or storage-node outage test was run. The Maven startup command compiled main and test sources but did not execute the unit test suite. No tracked application files were changed.

