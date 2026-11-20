# MiniDrive — Improvement Backlog

> Derived from the HLD's own "Known Limitations" section plus independent code-level analysis.
> Priority: **P0** = production blocker, **P1** = serious gap, **P2** = quality/scale improvement.
> Effort: **S** (hours), **M** (days), **L** (weeks).

---

## 1. Security (highest priority — all P0)

### 1.1 Transport security (TLS everywhere)
- **Problem:** No `server.ssl` block, `scheme: http` in `infra/prometheus.yml`, plain-HTTP Compose port mappings, JDBC URL without `sslmode`.
- **Why it matters:** Credentials, JWTs, file bytes, and share tokens all travel in plaintext on every leg (browser→API, API→Postgres, API→Redis, API→MinIO).
- **Fix:**
  - Add TLS termination (reverse proxy like nginx/Caddy, or `server.ssl.*` in Spring).
  - Enforce `sslmode=verify-full` on the JDBC URL.
  - Enable Redis TLS (`spring.data.redis.ssl.enabled=true`) and AUTH.
  - Enable MinIO TLS and switch client endpoints to `https`.
- **Effort:** M

### 1.2 JWT revocation / logout / refresh rotation
- **Problem:** `RefreshToken.java`, `RefreshTokenRepository.java`, `V5__create_refresh_tokens.sql` are **empty placeholders**. Logout clears localStorage only; the token stays valid 24h. No denylist.
- **Why it matters:** Stolen token = 24h of full access. No way to force-logout a compromised account.
- **Fix:**
  - Implement `RefreshToken` entity + repository + migration.
  - Add `POST /api/auth/refresh` (rotate short-lived access + long-lived refresh).
  - Add `POST /api/auth/logout` that revokes the refresh token and adds the access-token `jti` to a Redis denylist with TTL = remaining expiry.
  - Shorten access-token TTL to ~15 min.
- **Effort:** M

### 1.3 Replace default credentials
- **Problem:** `application.yml` defaults to `minioadmin`/`minioadmin`; `infra/docker-compose.yml` falls back to `POSTGRES_PASSWORD:-minidrive`; Redis has **no AUTH at all**.
- **Why it matters:** Any local process (or leaked env) gets full bucket + DB + cache access.
- **Fix:**
  - Remove defaults from `application.yml`; fail fast on missing secrets.
  - Add `--requirepass` / ACL to Redis and configure `spring.data.redis.password`.
  - Use Docker secrets or an external secret manager in prod.
  - Generate strong random passwords in `.env.example` (or use `<your-secret-here>` placeholders that fail validation).
- **Effort:** S

### 1.4 Rate limiting on auth & share endpoints
- **Problem:** No rate-limit dependency in `pom.xml`; `/api/auth/login`, `/api/auth/register`, and `GET /api/share/**` are unlimited.
- **Why it matters:** Brute-force, credential stuffing, and share-token enumeration.
- **Fix:**
  - Add Bucket4j or Resilience4j rate limiter (Redis-backed for multi-instance).
  - Apply per-IP + per-account limits on auth endpoints.
  - Add a per-token + per-IP limit on share endpoints.
  - Add account lockout after N failed logins.
- **Effort:** M

### 1.5 Move JWT out of localStorage
- **Problem:** `frontend/src/utils/tokenStorage.ts` reads/writes `localStorage`; any XSS reads it.
- **Why it matters:** XSS → full account takeover.
- **Fix:**
  - Store refresh token in `HttpOnly; Secure; SameSite=Strict` cookie.
  - Keep short-lived access token in memory only (not persisted).
  - Add a CSP header that forbids inline scripts.
- **Effort:** M

### 1.6 Fix share-token leakage in URLs
- **Problem:** `GET /api/share/{token}` puts a bearer capability in the URL path — logged by proxies, browser history, referer headers.
- **Why it matters:** Anyone with log access can replay the share forever until revoked.
- **Fix:**
  - Prefer a POST with token in the body, or a one-time "redeem" endpoint that exchanges the URL token for a short-lived session cookie.
  - Add `Referrer-Policy: no-referrer` on share pages.
  - Add per-token view limits / password / expiry (all currently absent).
- **Effort:** M

### 1.7 Add CSP + security headers
- **Problem:** No explicit CSP; only default Spring Security headers.
- **Fix:** Add `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Permissions-Policy` in `SecurityConfig`.
- **Effort:** S

### 1.8 Harden Actuator IP allowlist for production
- **Problem:** Prometheus endpoint protected only by `127.0.0.1` `IpAddressMatcher` — fine for local, unusable if Prometheus is remote, and any local process can scrape.
- **Fix:** Support configurable trusted CIDRs; in prod, require mTLS or basic-auth on the scrape path.
- **Effort:** S–M

---

## 2. Data consistency & durability (P0–P1)

### 2.1 Two-store write consistency / reconciliation job
- **Problem:** Writes cross PostgreSQL + MinIO with no distributed transaction. Compensation is best-effort; crashes leave orphaned objects or metadata without bytes. No reconciliation job exists.
- **Why it matters:** Silent data loss or unbounded orphan growth.
- **Fix:**
  - Add a **periodic orphan sweep**: compare `object_locations` keys against bucket inventories; delete unreferenced keys older than a grace window.
  - Add a **metadata-without-bytes detector**: for each `object_locations` row, confirm object exists; flag/repair.
  - Consider an **outbox pattern**: write a `pending_writes` row in the same DB transaction, then a worker performs the storage write and marks it done.
  - Add idempotency keys to storage writes.
- **Effort:** L

### 2.2 Garbage collection for historical versions
- **Problem:** `FileService.delete` leaves historical version objects in place; every replace/restore writes a new immutable key. **No GC code exists anywhere.**
- **Why it matters:** Storage grows unbounded; after a file delete you can't even enumerate stranded keys from metadata.
- **Fix:**
  - Add a `deleted_at` / retention policy to `file_versions`.
  - Write a scheduled GC job: for versions past retention, delete the Silo object, then the row.
  - Add a `orphaned_objects` audit table to track keys scheduled for deletion.
  - Make deletion async (queue-based) so large cleanups don't block.
- **Effort:** L

### 2.3 Transactional upload completion hardening
- **Problem:** `UploadSessionService.complete` writes final object before metadata; on failure it deletes the object — but that delete can fail silently (logged only).
- **Fix:** Combine with 2.1 — record the storage write in an outbox before performing it, so retries can finish the cleanup deterministically.
- **Effort:** M

### 2.4 Staging-object cleanup for abandoned sessions
- **Problem:** `_uploads/<owner>/<session>/<index>` staging objects are cleaned on success/failure, but abandoned sessions (client disappears) leave staged chunks behind indefinitely.
- **Fix:** Add a scheduled reaper for `UploadSession` rows past `expires_at`; delete staged chunks then mark session `EXPIRED`.
- **Effort:** S–M

---

## 3. Reliability & replication (P1)

### 3.1 Enable replication by default in prod profile
- **Problem:** `MINIDRIVE_REPLICATION_ENABLED=false` and `MINIO_SECONDARY_ENABLED=false` by default. Single copy of every object.
- **Why it matters:** One node failure = permanent data loss.
- **Fix:**
  - Create a `prod` Spring profile that defaults both flags to `true` (still overridable).
  - Document the enabling procedure.
  - Add a **backfill job** to populate `object_replicas` for pre-existing single-copy objects when replication is turned on for the first time (currently undocumented — probably missing).
- **Effort:** M

### 3.2 Compose: gate secondary node behind a profile
- **Problem:** `minio-secondary` and `minio-secondary-init` start unconditionally even when the backend ignores them.
- **Fix:** Add `profiles: ["replication"]` to those services.
- **Effort:** S

### 3.3 Live-test replication & repair behavior
- **Problem:** Replication/fallback/repair are **not runtime-verified**. Unit tests exist but weren't run.
- **Fix:** Add a CI job that spins up two MinIO nodes, kills one mid-replication, asserts repair converges. Measure repair throughput and backoff under sustained node loss.
- **Effort:** M

### 3.4 Health-check the cache path
- **Problem:** Cache is fail-open, but there's no metric/alert when Redis is down for a long time (only throughput silently drops).
- **Fix:** Emit `minidrive_cache_failures_total` counter and alert when it spikes.
- **Effort:** S

---

## 4. Storage & performance (P2)

### 4.1 Multipart upload sizing config
- **Problem:** No `spring.servlet.multipart.*` size limits → framework default applies, undocumented.
- **Fix:** Set explicit `max-file-size` / `max-request-size`; align with the 5 GiB session cap.
- **Effort:** S

### 4.2 Request-size & upload-rate limits
- **Problem:** No request-size limits, no upload-rate throttling beyond session caps.
- **Fix:** Add reverse-proxy `client_max_body_size`; add per-user concurrent-upload limits; add bandwidth caps if needed.
- **Effort:** S–M

### 4.3 Add object-level encryption
- **Problem:** No server-side encryption configured on MinIO.
- **Fix:** Enable SSE-S3 (MinIO supports it with KES or built-in); document key management.
- **Effort:** M

### 4.4 Reduce per-request user lookup
- **Problem:** *(Interpretation from HLD)* The user is loaded from the database **twice per authenticated request** — once in `JwtAuthenticationFilter`, once in `FileService.currentUser`.
- **Fix:** Cache `UserDetails` in the `Authentication` principal (it already has it) and read from there in services; or add a short-lived request-scoped cache.
- **Effort:** S

### 4.5 Deduplication for version storage
- **Problem:** Every version/restore writes a fresh full object — no content-addressed deduplication.
- **Fix:** Store objects by SHA-256 content hash; reuse the object when the hash already exists. Add a `content_objects` table mapping hash → refcount.
- **Effort:** L (big win for storage)

---

## 5. Observability (P1)

### 5.1 Deploy Grafana in Compose
- **Problem:** Grafana is **config-only**, no Compose service, dashboard provider path points to a **local Windows path**.
- **Fix:**
  - Add a `grafana` service to `infra/docker-compose.yml` (or a `monitoring` profile).
  - Fix the dashboard provider path to use `${GF_DASHBOARDS_PATH:/var/lib/grafana/dashboards}` or a repo-relative path.
  - Verify provisioning in CI.
- **Effort:** S

### 5.2 HTTP percentile histograms
- **Problem:** HLD says HTTP percentile histograms are unavailable.
- **Fix:** Enable Micrometer `http.server.requests` percentile config in `application.yml` (`management.metrics.distribution.percentiles-histogram`).
- **Effort:** S

### 5.3 PostgreSQL query timing metrics
- **Problem:** Not collected (needs exporter config or pg_stat_statements).
- **Fix:** Enable `pg_stat_statements` and add queries to the dashboard.
- **Effort:** S–M

### 5.4 Alerting rules
- **Problem:** No alert rules defined anywhere.
- **Fix:** Add Prometheus `rules.yml` for: cache failure rate, storage node down, repair backlog size, auth failure rate, disk usage.
- **Effort:** S

### 5.5 Correlation ID in the frontend
- **Problem:** Backend generates/echoes `X-Correlation-ID`, but the frontend Axios client doesn't send one — cross-tier traceability is lost.
- **Fix:** Generate a UUID per request in `frontend/src/api/client.ts` and attach it; include it in error UI for support.
- **Effort:** S

---

## 6. API & code quality (P2)

### 6.1 Remove dead placeholder files
- **Problem:** `TrashController`, `TrashService`, `UserController`, `UserService`, `Share.java`, `ShareService`, `RefreshToken*`, `V5__create_refresh_tokens.sql` are **empty**. They're noise in every file listing and mislead readers.
- **Fix:** Either implement or delete. If deferring, move to a `docs/roadmap.md` note instead of empty files.
- **Effort:** S

### 6.2 Delete or wire the single-shot upload path
- **Problem:** `frontend/src/api/files.ts::uploadFile` has **no caller** in the UI; only the resumable path is used.
- **Fix:** Remove `uploadFile`, or wire it to a small-file path. Two parallel upload paths is confusing.
- **Effort:** S

### 6.3 OpenAPI / Swagger spec
- **Problem:** No OpenAPI spec; API surface only discoverable by reading controllers.
- **Fix:** Add `springdoc-openapi-starter-webmvc-ui`; publish `/v3/api-docs` and Swagger UI behind auth.
- **Effort:** S–M

### 6.4 Consistent error response contract
- **Problem:** `GlobalExceptionHandler` exists, but share-token errors deliberately collapse "unknown / revoked / expired" into one message (good) — this is inconsistent with other errors that expose reason codes.
- **Fix:** Document the error contract (RFC 7807 Problem+JSON) and apply uniformly, keeping the deliberate opacity for share tokens.
- **Effort:** S

### 6.5 Pagination on file listing
- **Problem:** File listings return full folder contents; no pagination.
- **Fix:** Add `limit`/`cursor` params; enforce max page size.
- **Effort:** S

### 6.6 Batch operations for large folders
- **Problem:** Deleting 1,000 files = 1,000 requests.
- **Fix:** Add `DELETE /api/files?ids=...` bulk endpoint (transactional, permission-checked).
- **Effort:** S–M

---

## 7. Testing & runtime verification (P0 for credibility)

### 7.1 Run the test suites and publish results
- **Problem:** Tests exist at many layers but **none were executed** for the documentation. Test presence ≠ test passage.
- **Fix:**
  - Add a CI pipeline (GitHub Actions / GitLab CI) that runs `mvn verify` and `npm test` on every PR.
  - Publish coverage (JaCoCo + Vitest coverage).
  - Add a "Tests Passing" badge to the README.
- **Effort:** M

### 7.2 Add a smoke test in CI against real dependencies
- **Problem:** No end-to-end test against real Postgres/Redis/MinIO in CI.
- **Fix:** Use `testcontainers` or a Compose-up smoke job that exercises: register → login → create folder → start upload → upload chunks → complete → list → download → share → revoke share.
- **Effort:** M

### 7.3 Runtime-verify cache fail-open
- **Problem:** Fail-open semantics "not runtime-verified."
- **Fix:** Add an integration test that kills Redis mid-request and asserts the API still serves from Postgres with a documented latency delta.
- **Effort:** S

### 7.4 Runtime-verify share revocation & expiry
- **Problem:** Not verified; correctness rests on unrun tests.
- **Fix:** Add a live test: create share → access → revoke → access → expect 404.
- **Effort:** S

### 7.5 Load-test baselines
- **Problem:** `infra/load-testing/minidrive.js` exists but no published results.
- **Fix:** Run smoke/baseline/ramp/ramp10 profiles on representative hardware; publish results in `artifacts/` and link from README.
- **Effort:** S–M

---

## 8. Documentation & project hygiene (P1)

### 8.1 Fill or delete `docs/ARCHITECTURE.md` and `docs/LLD.md`
- **Problem:** Both are **empty files**. The HLD says so explicitly.
- **Fix:** Move the HLD content into `ARCHITECTURE.md`; write a real LLD covering class-level design, DB schema, API contracts, and sequence diagrams (some already exist in the HLD — promote them).
- **Effort:** M

### 8.2 Add a proper README with quickstart
- **Problem:** Deployment section is referenced but the repo has no evident polished quickstart in the excerpt.
- **Fix:** README with: prerequisites, `docker compose up`, backend/frontend run commands, test commands, default local URLs, troubleshooting.
- **Effort:** S

### 8.3 Document the "enabling replication on existing data" procedure
- **Problem:** HLD flags this as an open question — probably no backfill mechanism.
- **Fix:** Implement a one-shot backfill command (Spring `CommandLineRunner` gated by a flag) that populates `object_replicas` for existing keys, then document it.
- **Effort:** M

### 8.4 Add `.env.example` validation
- **Problem:** `.env.example` ships weak defaults; nothing fails when a real secret isn't set.
- **Fix:** Startup validator that fails fast if `JWT_SECRET` is missing or < 32 chars, `MINIO_SECRET_KEY == minioadmin`, etc. Document required vars in README.
- **Effort:** S

### 8.5 Roadmap file
- **Problem:** Deferred work is scattered across code comments ("Phase 7.1", "deferred to a later phase").
- **Fix:** Create `docs/ROADMAP.md` with all deferred items consolidated; link from README.
- **Effort:** S

### 8.6 Remove `artifacts/` or document what it contains
- **Problem:** Historical run artifacts exist but "were not reproduced or validated against the current code."
- **Fix:** Either delete, or add `artifacts/README.md` explaining what each bundle is and when it was produced.
- **Effort:** S

---

## 9. Operational / deployment (P1)

### 9.1 Production Compose / Helm chart
- **Problem:** `infra/docker-compose.yml` is explicitly **local development only**. No prod deployment artifact exists.
- **Fix:** Provide either a `docker-compose.prod.yml` (with TLS, secrets, restart policies, resource limits) or a Helm chart / K8s manifests. Document the externally operated TLS boundary the HLD references.
- **Effort:** L

### 9.2 Database least-privilege role
- **Problem:** One application account owns every table.
- **Fix:** Separate migration role (`flyway`) from application role (DML-only). Create read-only role for reporting.
- **Effort:** S

### 9.3 Backup & restore procedure
- **Problem:** No backup strategy documented for Postgres or MinIO.
- **Fix:** Document `pg_dump`/WAL archiving + MinIO bucket replication or `mc mirror` schedule. Test restore.
- **Effort:** M

### 9.4 Health & readiness probes for containers
- **Problem:** Compose services have no healthchecks for the backend/frontend (host-run); minio/minio-init bootstrap exists but postgres/redis healthchecks unclear from excerpt.
- **Fix:** Add `healthcheck:` to all Compose services; add K8s liveness/readiness if containerized.
- **Effort:** S

### 9.5 Resource limits
- **Problem:** No container CPU/memory limits.
- **Fix:** Add `deploy.resources.limits` to Compose services; set JVM heap via `JAVA_OPTS`.
- **Effort:** S

### 9.6 Secret management
- **Problem:** Secrets via env vars in `.env` files; HLD notes no secret manager implemented.
- **Fix:** Document an integration with Vault / AWS Secrets Manager / sealed-secrets; provide a reference implementation.
- **Effort:** M–L

---

## 10. Product features (nice-to-have, P2)

| Feature | Why | Effort |
|---------|-----|--------|
| **Trash / restore** | Deletion is currently permanent; `TrashController`/`TrashService` are empty stubs | M |
| **User profile management** | `UserController`/`UserService` empty; only `/me` exists | S |
| **Direct-share (user-to-user) flow** | `Share.java`/`ShareService` empty; only link sharing works | M |
| **Password reset / email verification** | Not present | M |
| **Share-link password + view limits** | Currently pure capability tokens | S |
| **Folder sharing** | Only files are shared | M |
| **Search** | No search endpoint or index | M–L |
| **Quotas / per-user storage limits** | No enforcement anywhere | M |
| **Audit log** | No per-action audit trail | M |
| **2FA / TOTP** | Not present | M |

---

## Suggested execution order

**Sprint 1 (production blockers):**
1. TLS everywhere (1.1)
2. Remove default credentials + Redis AUTH (1.3)
3. CI pipeline running existing tests (7.1)
4. Fail-fast config validation (8.4)

**Sprint 2 (auth hardening):**
5. Refresh tokens + logout + denylist (1.2)
6. Rate limiting on auth/share (1.4)
7. JWT out of localStorage + CSP (1.5, 1.7)

**Sprint 3 (data integrity):**
8. Orphan reconciliation job (2.1)
9. Staging reaper (2.4)
10. Version GC design + implementation (2.2)

**Sprint 4 (reliability):**
11. Replication on by default in prod profile + backfill (3.1, 8.3)
12. Compose profile gating (3.2)
13. Live replication tests in CI (3.3)

**Sprint 5 (observability + docs):**
14. Grafana in Compose + fix path (5.1)
15. Alert rules (5.4)
16. Fill ARCHITECTURE.md / LLD.md / ROADMAP.md (8.1, 8.5)

**Sprint 6 (polish):**
17. Remove dead files (6.1, 6.2)
18. OpenAPI spec (6.3)
19. Pagination (6.5)
20. Load-test baselines (7.5)

---

## The one-sentence summary

> The codebase is architecturally sound but **operationally unfinished** — fix transport security, credential defaults, token lifecycle, and add a reconciliation/GC layer before calling it production-ready; everything else is quality-of-life.



   LLD

# MiniDrive — Suggested Improvements (prioritized)

Legend
- Priority: P0 (critical / security / data loss) · P1 (high) · P2 (medium) · P3 (nice-to-have)
- Effort: S (<1 day) · M (1–3 days) · L (>3 days)
- Impact: what gets better if fixed

---

## P0 — Critical / security / data-integrity

### SEC-01 · Add refresh tokens
- Problem: `entity/RefreshToken.java`, `repository/RefreshTokenRepository.java`,
  `dto/auth/RefreshTokenRequest.java`, and `V5__create_refresh_tokens.sql` are 0-byte
  placeholders. Sessions die hard at 24 h; users must re-login.
- Recommendation: implement refresh-token table (hashed token, user_id, expires_at,
  revoked_at, rotation chain). Add `POST /api/auth/refresh` and `POST /api/auth/logout`.
  Rotate on every refresh; revoke the whole chain on reuse detection.
- Effort: M · Impact: real session lifecycle, removes the "log in every day" pain.

### SEC-02 · Add logout + JWT revocation
- Problem: `JwtService.generateToken` writes no `jti`, issuer, or audience; the app keeps
  no denylist. An issued JWT is valid for the full 24 h even after logout. Only deleting
  the user row invalidates a token.
- Recommendation: add `jti` to the JWT; keep a Redis-backed denylist keyed by `jti`
  with TTL = remaining token lifetime. Wire `POST /api/auth/logout` to add the current
  `jti`. Add issuer/audience claims and validate them.
- Effort: M · Impact: stolen tokens can be killed; enables real logout.

### SEC-03 · Login / registration rate limiting
- Problem: no rate limiting, lockout, or throttling anywhere; `pom.xml` has no
  rate-limiter dependency. Credential stuffing and brute force are unmitigated.
- Recommendation: add Bucket4j (or Resilience4j) keyed by email + IP on
  `/api/auth/login` and `/api/auth/register`; exponential backoff after N failures;
  return 429 with `Retry-After`.
- Effort: S · Impact: closes a well-known attack path.

### UPL-01 · Same-index / different-bytes retry corrupts checksums
- Problem: `UploadSessionService.ingestChunk` writes to the deterministic staging key
  *before* checking whether a chunk row already exists, and does not compare the retry's
  checksum with the stored one. A same-size retry with different bytes overwrites staging
  while the old checksum stays in PostgreSQL → completion fails with a checksum mismatch.
- Recommendation: compute SHA-256 first, look up `(session_id, chunk_index)`; if a row
  exists, compare checksums:
  - match → return `duplicate=true` without re-writing storage,
  - mismatch → 409 Conflict, do not overwrite.
  Only then upload to staging.
- Effort: S · Impact: eliminates a hard-to-debug resume failure.

### STO-01 · No read fallback when replication is disabled
- Problem: in default (non-replicated) mode, `RoutingStorageService.download` reads
  from exactly the recorded node. If it is down, reads fail — no fallback at all.
- Recommendation: when `replication.enabled=false`, treat all configured nodes as
  candidate mirrors on read; verify size (and checksum if available) before streaming.
  Keep writes on the recorded node. Add a small circuit breaker around the primary.
- Effort: M · Impact: removes a single-point-of-failure for every download.

### STO-02 · Replication delete leaves stale `object_replicas` rows
- Problem: `RoutingStorageService.delete` removes the object from every node and deletes
  `object_locations`, but leaves `object_replicas` rows behind (marks other nodes
  `FAILED` even after successful deletes). Those stale rows become zombie repair
  candidates and can repopulate deleted data if a source row is still `HEALTHY`.
- Recommendation: on delete, also delete every `object_replicas` row for the key in the
  same transaction as the `object_locations` delete; have repair skip keys with no
  `object_locations` row.
- Effort: S · Impact: prevents resurrection of deleted objects and wasted repair cycles.

### FOL-01 · Folder delete leaks file objects in storage
- Problem: `files.folder_id ON DELETE CASCADE` deletes rows when a folder is deleted,
  but no code deletes the corresponding objects from MinIO/object storage. Every file
  under a deleted folder becomes an orphan object (plus all historical version objects).
- Recommendation: make folder delete a service-level orchestrated operation:
  1) collect object keys of every file (and its versions) under the subtree,
  2) delete rows in a transaction, and enqueue object deletions via an outbox table
     processed after commit (best-effort with retries).
- Effort: M · Impact: stops silent storage growth on every folder delete.

---

## P1 — High

### SEC-04 · `AuthController` bypasses the service layer for `/api/auth/me`
- Problem: `AuthController` injects `UserRepository` directly — the only
  controller→repository dependency in the codebase. `dto/user/UserResponse.java` is a
  0-byte placeholder, and the endpoint returns an ad-hoc `Map.of(...)`.
- Recommendation: introduce `UserService.getCurrentUser()` and a real `UserResponse`
  DTO; delete the `UserRepository` injection from the controller. This also lets
  `/me` share logic with future profile endpoints.
- Effort: S · Impact: restores the layer contract; gives a stable DTO.

### SEC-05 · `/api/auth/me` throws 500 if the user was deleted between the filter and controller
- Problem: `orElseThrow()` with no argument throws `NoSuchElementException`, which
  `GlobalExceptionHandler` does not map → 500. Rare race, but ugly.
- Recommendation: `orElseThrow(() -> new ResourceNotFoundException("User not found"))`
  and add a 401 mapping for "authenticated subject no longer exists".
- Effort: S · Impact: correct status code; avoids noisy 500s.

### SEC-06 · No password change or re-hash policy
- Problem: BCrypt strength is library default; no re-hash-on-login, no
  password-change endpoint.
- Recommendation: add `POST /api/auth/change-password` (verify old, hash new,
  revoke refresh chain); re-hash on successful login if encoder reports upgrade
  (`PasswordEncoder.upgradeEncoding`). Set an explicit BCrypt strength (e.g. 12).
- Effort: S · Impact: standard account hygiene; future-proofs hashing cost.

### UPL-02 · Expired upload sessions are never swept
- Problem: `expiresAt` is checked on write, but there is no scheduler that transitions
  `INITIATED → EXPIRED`, and `findByExpiresAtBefore` has no caller. Sessions and staging
  objects persist forever unless the user explicitly aborts.
- Recommendation: add `@Scheduled` cleanup that (a) marks due sessions `EXPIRED`,
  (b) deletes their staging objects, (c) deletes their chunk rows. Run it in small
  batches; log per-batch counts.
- Effort: M · Impact: bounded storage growth; honest session state.

### UPL-03 · Abort can race with an in-flight chunk
- Problem: no concurrency contract between `abort` and `ingestChunk`. A chunk that is
  in flight when abort runs can re-create a chunk row / staging object after cleanup.
- Recommendation: take the session row lock (`PESSIMISTIC_WRITE`) at the start of both
  `abort` and `ingestChunk`; abort no-ops if `status != INITIATED` inside the lock.
- Effort: S · Impact: removes a class of orphan staging objects.

### STO-03 · Legacy objects have no replica checksum → replication fallback silently disabled
- Problem: V15 seeds `object_locations` only; V16 creates an empty `object_replicas`
  table. Enabling replication on existing data gives you no expected size/checksum, so
  the verified fallback path can't be used for legacy files.
- Recommendation: ship a one-shot backfill job that walks `object_locations`, copies
  each object's actual size and SHA-256 into `object_replicas` on the recorded node, and
  marks it `HEALTHY`. Gate it behind a feature flag; make it resumable.
- Effort: M · Impact: makes enabling replication a safe operation.

### STO-04 · Repair scan cannot find *missing* rows
- Problem: `repairOnce` only scans existing non-`HEALTHY` `object_replicas` rows.
  A node that was never recorded (or whose row was deleted) is never repaired.
- Recommendation: add a low-frequency "inventory reconciler" that iterates
  `object_locations`, and for each key checks each enabled node for presence and hash.
  Emit missing/incompatible rows into `object_replicas` as `PENDING` so the existing
  healer picks them up.
- Effort: L · Impact: repair becomes actual reconciliation, not only row repair.

### STO-05 · No distributed lock around repair
- Problem: the repair guard is a process-local `ConcurrentHashMap`-style set. Two app
  instances will both try to heal the same key/node; `mark()` does update-then-insert
  without a lock, so concurrent updates can race on the primary key.
- Recommendation: take a Redis or PostgreSQL advisory lock per (object_key, node_id)
  with a short TTL; wrap `mark()` in an `INSERT ... ON CONFLICT DO UPDATE` upsert.
- Effort: M · Impact: safe horizontal scaling; no duplicated work.

### STO-06 · No transactional outbox between storage and PostgreSQL
- Problem: every storage mutation is followed by DB writes (and vice versa) with only
  best-effort compensation. `mark(FAILED)` can itself throw and escape `replicate`;
  a `mark(HEALTHY)` DB failure after a successful upload leaves an object with no
  metadata. These are the orphan and missing-copy cases the doc calls out.
- Recommendation: introduce an `outbox` table. Inside the same DB transaction that
  writes file/version/replica metadata, insert an outbox row describing the object
  operation; a background worker executes it with retries and idempotency keys
  (object key + expected hash). For deletes: outbox row drives object deletion.
- Effort: L · Impact: this is the single biggest structural reliability win.

### CACHE-01 · Global generation makes every listing cold on every mutation
- Problem: a single Redis token invalidates *all* owners' listings. One file rename in
  one folder forces every listing to miss.
- Recommendation: scope the generation per (owner, folder) and keep a small owner-level
  epoch for cascades. Folder delete advances only the affected subtree tokens.
- Effort: M · Impact: much higher steady-state hit ratio, fewer DB hits under load.

### CACHE-02 · Cache invalidations are lost if Redis is down post-commit
- Problem: `advanceQuietly` logs and swallows Redis failures. After a commit, stale
  entries remain reachable for up to 60 s (plus TTL drift).
- Recommendation: write invalidations into a `cache_invalidations` outbox row in the
  same transaction; a worker drains it into Redis with retry. Fall back to a short TTL
  (e.g. 10 s) when the outbox is non-empty.
- Effort: M · Impact: freshness is restored once Redis comes back, without a 60 s gap.

### EXC-01 · No catch-all handler; response shapes are inconsistent
- Problem: `GlobalExceptionHandler` covers specific types but has no fallback. Bodies
  differ by path: `{"errors": {..}}` (validation), `{"error": "..."}` (business), empty
  (security), Spring default (unhandled). Malformed JSON and unsupported media types
  have no defined contract.
- Recommendation: adopt a single schema, e.g.
  `{ "error": { "code": "FILE_NOT_FOUND", "message": "...", "fields": {...}, "traceId": "..." } }`
  and add explicit handlers for `HttpMessageNotReadableException`,
  `HttpMediaTypeNotSupportedException`, and a generic `Exception` that returns 500 with
  the same schema and logs a stack trace with the correlation ID.
- Effort: M · Impact: predictable client behavior; cleaner logs.

### EXC-02 · Streaming downloads cannot report mid-stream failure as 502
- Problem: once `StreamingResponseBody` starts, status 200 is committed. A storage
  failure mid-stream produces a broken connection, not the JSON 502.
- Recommendation: (a) in replication-enabled mode this is already validated up front —
  extend that pattern to non-replicated mode by at least pre-reading the first N bytes
  and checking `Content-Length`; (b) document the failure mode; (c) add a `Retry-After`
  on the initial request if storage is down before the stream begins.
- Effort: S · Impact: sets correct client expectations; avoids silent truncation.

### TEST-01 · No unit tests for `JwtService` / `AuthService`
- Problem: `backend/src/test/java/com/minidrive/service/` contains only a `.gitkeep`;
  there is no `security/` test package. Coverage is controller/integration only.
- Recommendation: add focused unit tests for token generation/parsing/expiry boundary,
  clock-skew behavior, login happy/duplicate/bad-credential paths.
- Effort: S · Impact: fast feedback on the security core.

### TEST-02 · Flyway migrations are not exercised by tests
- Problem: the test profile disables Flyway and uses H2 `create-drop`. Every real
  migration (V1, V6–V16) is untested in CI.
- Recommendation: add a Testcontainers-based `PostgreSQL` profile that runs Flyway
  migrations and a small set of schema assertions; use it for repository and routing
  tests that depend on real constraints.
- Effort: M · Impact: catches migration drift before production.

---

## P2 — Medium

### FOL-02 · No frontend delete control for folders
- Problem: `DELETE /api/folders/{id}` exists; `frontend/src/api/folders.ts` has no
  `deleteFolder`, and no caller exists.
- Recommendation: add `deleteFolder(id)` to the API module, a confirm dialog in
  `DrivePage`, and a refresh-on-success. Show "moves N files to trash" if trash lands.
- Effort: S · Impact: users can finally delete folders from the UI.

### FOL-03 · `useDriveLocation` fetches each ancestor one-by-one (up to 32 round-trips)
- Problem: it calls `getFolder` per parent link. Slow on deep trees; hammers the server.
- Recommendation: add `GET /api/folders/{id}/ancestors` returning the full chain, or
  include `path` in `FolderResponse`.
- Effort: S · Impact: 1 request instead of N; snappier breadcrumbs.

### OBS-01 · No histogram buckets → no p95/p99 from Prometheus
- Problem: Micrometer timers are configured with no explicit buckets. Prometheus
  `_sum`/`_count` give averages only; you cannot compute percentiles.
- Recommendation: publish `publishPercentileHistogram()` with tuned SLO buckets
  (e.g. 10, 25, 50, 100, 250, 500, 1000 ms) on file-operation, upload-session, and
  node-operation timers.
- Effort: S · Impact: latency SLOs become measurable from Prometheus/Grafana.

### OBS-02 · No cache hit/miss/eviction metrics
- Problem: caching is silent. You cannot answer "is the cache helping?" or
  "how often does Redis fail open?".
- Recommendation: emit counters `minidrive_cache_requests_total{result=hit|miss|error}`
  and `minidrive_cache_generation_advances_total{result=ok|error}`, plus a gauge for
  outbox depth (see CACHE-02).
- Effort: S · Impact: cache is observable and tunable.

### OBS-03 · No replica-backlog / repair-lag metric
- Problem: healing counters exist, but nothing exposes how many rows are pending
  (`status != HEALTHY AND next_attempt_at <= now()`). You can't alert on stuck repair.
- Recommendation: add a scheduled gauge `minidrive_replication_backlog` and
  `minidrive_replication_oldest_pending_seconds`.
- Effort: S · Impact: operational visibility for the recovery loop.

### OBS-04 · Completion log omits exception info
- Problem: `CorrelationIdFilter` logs status/duration but not the thrown exception;
  `GlobalExceptionHandler` doesn't log at all. Debugging an error requires correlating
  separately by correlation ID.
- Recommendation: in the advice, log `warn`/`error` with `correlation_id`, exception
  class, and (for 5xx) stack trace; keep client bodies free of internals.
- Effort: S · Impact: faster incident response.

### EXC-03 · Validation handler drops duplicate field messages
- Problem: `handleValidation` puts messages in a `Map` keyed by field name — a second
  violation on the same field replaces the first.
- Recommendation: change to `Map<String, List<String>>` and update DTO tests.
- Effort: S · Impact: clients see all violations.

### EXC-04 · No test asserting locking → 409
- Problem: `handleConcurrencyConflict` exists but there is no test.
- Recommendation: add a controller/service test that forces an
  `OptimisticLockingFailureException` and asserts 409 + stable error code.
- Effort: S · Impact: locks stay correctly surfaced.

### CODE-01 · 0-byte placeholders should be implemented or deleted
- Problem: `TrashController`, `TrashService`, `UserController`, `UserService`,
  `RefreshToken`, `Share`, `ShareRepository`, `ShareMapper`, `UserMapper`, `JacksonConfig`,
  `dto/user/*`, `dto/share/*`, `dto/auth/RefreshTokenRequest`, `StoragePathResolver`,
  `UnauthorizedException`, `ValidationException`, `storage/StorageException` are all 0 bytes.
- Recommendation: per file, pick one of (a) implement, (b) delete the file and the
  corresponding migration slots, or (c) leave a TODO with an issue number. Every
  0-byte file is a future footgun and a doc liar.
- Effort: S per file · Impact: codebase honest with itself.

### CODE-02 · `storage/SiloStorageService.java` is dead code in `main`
- Problem: no main-source file references it; only the opt-in live integration test
  constructs it. It is a second, unmaintained implementation of `StorageService` and
  a source of confusion.
- Recommendation: move it to `src/test/java/.../integration/support/` (its only real
  user) or annotate it `@Profile("silo-direct")` and document why it exists.
- Effort: S · Impact: less ambiguity about which storage implementation is live.

### CODE-03 · `storage/StorageException` name collision
- Problem: `com.minidrive.storage.StorageException` is 0 bytes; the live type is
  `com.minidrive.exception.StorageException`. Two packages, same name — easy to import
  wrong.
- Recommendation: delete the placeholder.
- Effort: S · Impact: removes an easy bug.

### CODE-04 · `FileService` is doing too much
- Problem: the doc calls it "largest service" — CRUD, upload, replace, download,
  versions, restore, cache hooks all in one class.
- Recommendation: extract `FileVersionService`, `FileDownloadService`, and
  `FileMutationService` sharing `FileAccessService`. Keep `FileService` as a facade
  if call sites prefer it. This is a refactor, not a rewrite.
- Effort: M · Impact: testability and reviewability.

### TEST-03 · No concurrent race tests
- Problem: the doc lists several races as "unverified": repair vs concurrent repair
  across instances, replace/restore simultaneously, abort vs in-flight chunk.
- Recommendation: add JUnit + Testcontainers + `@RepeatedTest` (or Awaitility) tests
  that exercise the row-lock paths and the outbox worker.
- Effort: M · Impact: lock design verified, not just asserted in prose.

---

## P3 — Nice-to-have

### PERF-01 · Review indexes against actual query shapes
- Problem: `file_permissions`, `share_links`, `upload_sessions.expires_at`,
  `upload_chunks(session_id, chunk_index)` are indexed, but there is no EXPLAIN-based
  review of listing / version / repair scans.
- Recommendation: capture representative queries, run `EXPLAIN ANALYZE`, and add
  covering indexes where the plan seq-scans.

### PERF-02 · k6 profiles don't exercise resumable / share / version paths
- Problem: the load test only uploads via multipart and downloads once. The dominant
  UI path (resumable) and the public-share path are unmeasured.
- Recommendation: add scenarios for resumable completion, share-link create+read,
  version restore, and folder listing (to exercise the Redis cache under load).

### OPS-01 · Add DB pool / Redis / MinIO saturation metrics
- Problem: the doc calls out that current metrics cannot distinguish app CPU from
  DB/Redis/storage saturation.
- Recommendation: publish HikariCP pool metrics (`hikaricp.connections.*`), Lettuce
  command latency, and rely on the MinIO/Prometheus jobs already in `infra/` for
  storage. Add Grafana panels.

### DOC-01 · Adopt a stable error-code catalog
- Problem: error bodies carry free-form messages; nothing stable for clients to switch on.
- Recommendation: enumerate codes (`AUTH_INVALID_CREDENTIALS`, `FILE_NOT_FOUND`,
  `STORAGE_UNAVAILABLE`, `UPLOAD_CHECKSUM_MISMATCH`, `CONFLICT_STALE_VERSION`, …) and
  return `{ code, message, traceId }`. Tests assert codes, not strings.

### DOC-02 · Document the "0-byte placeholder" status visibly
- Problem: the LLD calls placeholders out repeatedly; the repo does not.
- Recommendation: add `README.md` sections per package listing placeholders and their
  intended owner; add a `// PLACEHOLDER — see issue #NNN` header to each 0-byte file.

---

## Suggested sequencing (first 4 sprints)

Sprint 1 (P0 + cheap P1):
- SEC-01, SEC-02, SEC-03
- UPL-01, STO-02
- SEC-04, SEC-05, TEST-01

Sprint 2 (data integrity + storage):
- FOL-01, UPL-02, UPL-03
- STO-01, STO-03
- EXC-01, EXC-03, EXC-04

Sprint 3 (reliability + observability):
- STO-05, STO-06 (outbox)
- CACHE-02, OBS-01, OBS-02, OBS-03, OBS-04
- TEST-02

Sprint 4 (scale + hygiene):
- CACHE-01, STO-04
- CODE-01/02/03/04, FOL-02, FOL-03
- PERF-01/02, OPS-01, DOC-01/02
   