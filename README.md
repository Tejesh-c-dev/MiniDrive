# MiniDrive
A mini version alternative for google drive

## Production deployment boundary

The repository does not provide a complete production deployment. The Compose
file is for local development and starts PostgreSQL, Redis, primary and
secondary Silo, and one-shot private-bucket initialization jobs. It does not
start the Spring backend, React frontend, HTTPS ingress, or monitoring services.
Its host ports bind to loopback (`55432`, `56379`, `9000`/`9001`, and
`9100`/`9101`); PostgreSQL and both Silo nodes use named volumes
(`postgres_data`, `minio_data`, and `minio_secondary_data`), while Redis has no
persistent volume. Keep these dependencies on private networks in any deployment.
Redis uses ACL authentication with separate application and monitoring users.
Loopback binding limits host exposure but does not replace Redis authentication.

For production, run the backend with the Spring `prod` profile and supply the
required variables listed below through the deployment environment or an
orchestrator's secret injection facility. This repository does not select a
cloud provider, secret manager, frontend hosting service, or proxy. Do not use
the development Compose credentials or the development `.env.example` as
production values. PostgreSQL, Redis, and Silo must be provisioned separately
or via an operator-managed private network. The React frontend is built and
hosted separately. Configure internal network rules so only the backend can
reach its data services, and do not publish their administrative ports to the
public internet. Use a least-privilege PostgreSQL service account and Silo
credentials scoped to the buckets the application needs.

Public HTTPS must terminate at an externally operated reverse proxy, load
balancer, or equivalent ingress. TLS certificates, private keys, renewal, and
expiry monitoring belong to that operator. The ingress forwards requests to the
backend over a private, access-controlled network; use TLS on that hop when the
network is not trusted. The backend does not provide public TLS in this
repository. The production profile disables Spring forwarded-header processing.
For MD-012 rate-limit client IPs, set `MINIDRIVE_RATE_LIMIT_TRUST_PROXY=true`
and `MINIDRIVE_RATE_LIMIT_TRUSTED_PROXIES` to the ingress source IPs/CIDRs only.
The ingress must remove client-supplied `X-Forwarded-For` and append the address
it observed. The limiter uses the rightmost forwarded address only when the
socket peer matches that allowlist; arbitrary clients must not be able to reach
the backend directly or impersonate a trusted proxy.

The `prod` profile requires `DB_URL` (PostgreSQL JDBC URL), `DB_USERNAME`,
`DB_PASSWORD`, `JWT_SECRET` (at least 32 UTF-8 bytes), `REDIS_HOST`, `REDIS_PORT`,
`REDIS_USERNAME`, `REDIS_PASSWORD`, valid
`REDIS_PORT`, `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`,
`MINIO_BUCKET`, and `MINIDRIVE_CORS_ALLOWED_ORIGINS` (comma-separated exact
HTTPS origins, no wildcard or path). It rejects known development credential
values. The ingress trust settings above are also mandatory. The secondary Silo
variables `MINIO_SECONDARY_ENDPOINT`, `MINIO_SECONDARY_ACCESS_KEY`,
`MINIO_SECONDARY_SECRET_KEY`, and `MINIO_SECONDARY_BUCKET` become mandatory
when `MINIO_SECONDARY_ENABLED=true`. Missing or invalid required settings stop
startup with key names only; secret values are not included in diagnostics.
Supply these values to the backend process and keep them in the operator's
secret store, outside source control. Never pass real values on command lines
that are retained in shell history or process listings.

Example launch from `backend` after injecting the required environment values:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
.\mvnw.cmd spring-boot:run
```

Production Actuator exposure remains limited to health and Prometheus, with the
existing loopback-only security rules. The current Prometheus scrape endpoint
is reachable only from the backend host; remote scrape access is not configured.
Compose does not provide monitoring. MD-011 revocation state remains in PostgreSQL. MD-012 rate limits
depend on Redis and fail closed for protected requests when Redis is unavailable.
MD-013 CORS uses only the configured exact-origin allowlist.

### Minimum backup and recovery responsibilities

There are no repository backup or restore scripts and no automatic backup,
point-in-time recovery, off-site replication, or disaster-recovery guarantee.
Operators must configure and monitor PostgreSQL backups and protect Silo object
content independently. A metadata-only restore is insufficient: file metadata
and object bytes must represent a consistent recovery point. Quiesce writes or
use coordinated snapshots before backup; restore PostgreSQL and Silo content
from the matching recovery point, then run read-only verification of schema,
file/version records, object locations, representative object reads and hashes,
and authentication/revocation behavior before reopening traffic. Protect the
production secret set (especially `JWT_SECRET` and storage/database credentials)
in the secret manager's backup/recovery process; retaining the same JWT secret
avoids invalidating otherwise valid tokens. Assign an operator for retention,
backup-failure alerting, secret recovery, and periodic isolated restore drills.
These are operational prerequisites; this repository does not automate them.

## Local database

MiniDrive development PostgreSQL runs in Docker and is isolated from any PostgreSQL
installation on Windows. The container keeps PostgreSQL's internal port `5432`,
but publishes it only on loopback at `127.0.0.1:55432`.

From `C:\MiniDrive`, start the database with:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker ps
```

Wait for `minidrive-postgres` to show `(healthy)`. Verify the database explicitly:

```powershell
$env:PGPASSWORD="minidrive"
psql -h 127.0.0.1 -p 55432 -U minidrive -d minidrive -c "SELECT 1;"
psql -h 127.0.0.1 -p 55432 -U minidrive -d minidrive -c "SELECT current_user, current_database();"
```

Run the backend from the repository root by changing into the Maven project first:

```powershell
Set-Location C:\MiniDrive\backend
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

The default `dev` profile reads its datasource settings from the root `.env`
file. Copy `.env.example` to `.env` for a new checkout and keep the real `.env`
local. The explicit `prod` profile does not import this file.

## Local observability

For authenticated Redis, set `REDIS_USERNAME`, `REDIS_PASSWORD`,
`REDIS_EXPORTER_USERNAME`, and `REDIS_EXPORTER_PASSWORD` in the untracked `.env`.
Use distinct randomly generated hex passwords and never put real values in
commands or committed files. `.env.example` contains placeholders only.
Compose disables the Redis default user and initializes `minidrive_app` for
cache and atomic rate-limit operations and `minidrive_exporter` for monitoring.
Both credentials are required before Redis starts. For an existing local
service, inspect its Compose project/container and port before recreating Redis;
this service has no attached volume, and do not use `down -v` because PostgreSQL
and Silo volumes are persistent. Recreating Redis discards cache contents only.
The loopback-only port binding reduces network exposure but does not replace
Redis authentication.

The optional local monitoring setup is documented in
[`infra/monitoring/README.md`](infra/monitoring/README.md). The backend and
frontend still run on the host; PostgreSQL, Redis, and Silo run in Compose.
Prometheus and Grafana run as host processes so all management interfaces can
remain loopback-only while Prometheus satisfies the backend's loopback-only
Actuator policy. The optional Compose monitoring profile adds the PostgreSQL
and Redis exporters without changing normal application startup.

The provisioned Grafana dashboard uses the repository's `infra/grafana`
assets. Prometheus scrapes `/actuator/prometheus` on the configured backend
port 8080 and the local exporters and Silo-compatible metrics routes. The
current application exposes HTTP count/sum, JVM/process metrics, and bounded
file/storage/replication/spool metrics; HTTP latency percentiles are unavailable
because histogram buckets are not configured. PostgreSQL query timings and
per-container CPU/memory are also unavailable. Silo provides usable capacity,
incoming request and rejected-auth counts, and node metrics; its resource
endpoint reflects the Linux host view available to Silo and is not isolated
container telemetry.

Install the small Python validation dependency and run the repeatable
configuration checks from the repository root:

```powershell
python -m pip install -r infra/monitoring/requirements.txt
.\infra\monitoring\validate.ps1
```

This renders base and monitoring Compose configurations, checks scrape targets
against the application port/security configuration, validates dashboard and
provisioning references, and runs Prometheus `promtool`. Do not use the
development setup as production monitoring; production secret injection,
network policy, TLS, and remote scrape authentication are outside this
repository's monitoring deployment.

## Object storage (MinIO)

MiniDrive stores file objects in MinIO. Because MinIO stopped publishing its
community Docker images in 2026, the stack uses the community-maintained
[Silo fork](https://github.com/pgsty/minio), a drop-in MinIO replacement with
security patches and the full admin console.

PostgreSQL stores file metadata and ownership; Silo stores the file contents.
File ownership is taken from the authenticated user, and storage object keys are
generated by the backend.

The `infra/docker-compose.yml` stack starts PostgreSQL, Redis, and two independent Silo nodes:

- `minidrive-postgres` - PostgreSQL on `127.0.0.1:55432`
- `minidrive-minio` - MinIO API on `127.0.0.1:9000`, console on `127.0.0.1:9001`
- `minidrive-minio-init` - a one-shot bootstrap that waits for MinIO, creates the
  private `minidrive-files` bucket and exits. It is idempotent, so re-running
  `docker compose up` never fails because the bucket already exists.
- `minidrive-minio-secondary` - the optional second node on `127.0.0.1:9100`
  (console `9101`) with its own `minio_secondary_data` volume.
- `minidrive-minio-secondary-init` - idempotently creates its private bucket.

```powershell
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker ps
```

`--env-file .env` makes Compose read the credentials from the root `.env`; without
it Compose looks for `.env` next to the compose file (i.e. `infra/`).

Open the console at http://localhost:9001 and confirm the `minidrive-files` bucket
exists. The login is `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` from the root `.env`
(`minioadmin` / `minioadmin` when unset). You can also check it from the MinIO container, which bundles the `mc` client:

```powershell
docker exec minidrive-minio sh -c 'mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"'
docker exec minidrive-minio mc ls local/
```

The buckets are private: anonymous access is disabled, so objects are not publicly
readable or writable. The primary node keeps the existing `MINIO_ENDPOINT`,
`MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` and `MINIO_BUCKET` variables and stable ID
`primary`. To use both nodes, set `MINIO_SECONDARY_ENABLED=true` in `.env`; its
endpoint, credentials, and bucket are controlled by the `MINIO_SECONDARY_*`
variables in `.env.example`. New keys are deterministically assigned among
enabled nodes, while the PostgreSQL `object_locations` registry controls all
later reads and deletes. Existing versioned files and resumable staging objects
are backfilled to `primary` by Flyway V15. Keep node IDs stable and configure
every node referenced by that registry.

Replication is opt-in so existing single-node deployments retain their startup
and routing behavior. Set `MINIDRIVE_REPLICATION_ENABLED=true` only after both
Silo nodes and their private buckets are healthy. The configured `primary` node
is the sole write authority; each immutable content object (including historical
versions and completed resumable uploads) gets a verified replica status row in
PostgreSQL. Secondary outages leave the primary object available and mark the
replica failed with a bounded retry time. Reads validate size and SHA-256 before
returning bytes and can fall back to a verified secondary copy. A scheduled
repair pass copies from a verified surviving node and is also exposed through
the storage service's bounded `repairOnce()` operation for deterministic tests.
The repair metrics are `minidrive_replication_attempts_total`,
`minidrive_replication_healing_total`, and
`minidrive_storage_read_fallback_total`; node operation metrics continue to use
`minidrive_storage_node_operation_seconds`.

The real-MinIO storage integration test is opt-in and is not run by the ordinary
backend test suite. With a reachable MinIO/Silo server and its test credentials,
run it from `C:\MiniDrive\backend`:

```powershell
.\mvnw.cmd '-Plive-minio' `
  '-Dminidrive.live-minio.endpoint=http://127.0.0.1:9000' `
  '-Dminidrive.live-minio.access-key=minioadmin' `
  '-Dminidrive.live-minio.secret-key=minioadmin' `
  '-Dtest=SiloStorageServiceRealMinioLiveTest' test
```

The test creates and removes a disposable bucket and only uses objects under its
own run-specific prefixes. Replace the endpoint and credentials with those for
the real test server.

## Upload retries and integrity

Chunked upload session creation accepts an optional `Idempotency-Key` header.
The key is scoped to the authenticated caller and session-creation operation;
retries with the same metadata return the original session, while a changed
payload returns `400`. Completion is durably idempotent by the owner-scoped
upload session ID: repeating completion returns the file/version already
recorded on that session. The backend computes SHA-256 while streaming content
and stores it on the current file and its immutable version. Resumable completion
checks staged chunk hashes before finalizing metadata.

Physical content deduplication is deferred. The current deletion lifecycle treats
each file version as owning its object, so sharing object keys needs transactional
reference tracking before objects can safely be reused.

## Cleanup recovery when PostgreSQL is unavailable

Before writing an object, MiniDrive forces a write-ahead intent to an independent
filesystem journal at `MINIDRIVE_CLEANUP_JOURNAL_DIR` (default
`./data/cleanup-journal`). A committed database transaction resolves that intent;
a rollback leaves it for recovery. If compensation fails, MiniDrive also attempts
the existing PostgreSQL deletion job, while the independent intent remains as a
fallback if PostgreSQL cannot persist the job. The scheduled replay takes a
per-key process and filesystem lock, checks current file, version, chunk, and
folder-deletion references, and then retries the idempotent storage delete. A
referenced key is preserved and its redundant intent is resolved. Database and
storage errors leave the intent pending for later runs.

The journal directory must be on durable storage shared by every MiniDrive
instance and worker, with cross-process file locks supported. Do not use an
ephemeral container filesystem or a network filesystem with unreliable locking.
Keep adequate free space and monitor the journal error logs. If PostgreSQL and
Silo remain unavailable, entries remain pending. If the journal volume itself is
unavailable or lost, the application reports that both recovery paths failed and
operator reconciliation is required; there is no third independent copy. The
JDK cannot fsync directory metadata on Windows, so power-loss durability there
depends on the mounted filesystem. Linux deployment filesystems must support
directory fsync for the journal's full durability guarantee.
# Existing data migration to replication

Replication remains disabled by default. Before enabling it on a database that
already has files, run the integrity reconciliation once with
`MINIDRIVE_REPLICATION_ENABLED=false` and `MINIDRIVE_REPLICATION_BACKFILL=true`.
Keep all storage nodes that may hold existing objects configured and enabled
for this run. Before running, inspect the inventory and unresolved locations:

```sql
select count(*) as durable_objects
from (select object_key from file_versions union select object_key from files) objects;

select object_key
from (select object_key from file_versions union select object_key from files) objects
left join object_locations using (object_key)
where object_locations.object_key is null;
```

The backfill scans current file objects and version history in pages of 100,
streams each copy to calculate SHA-256 and length, and records primary and
secondary state only after the stream completes. Missing or corrupt secondary
copies are recorded `FAILED` for normal repair; the backfill does not create
copies. The `secondaryUnverified` count and per-key logs report those copies.
Missing/unreadable authoritative objects and records without a known location
make the run exit unsuccessfully and are logged by object key. A run with only
unverified secondary copies can complete because those copies are never trusted;
the required authoritative integrity metadata is verified, and normal repair
can create the secondary copy after replication is enabled.

After a successful run, confirm the report has `failed=0`, `missingLocation=0`,
and `skippedDeleting=0`. Resolve primary failures and missing locations; review
`secondaryUnverified` and its object logs as repair work. Then start normally with
`MINIDRIVE_REPLICATION_BACKFILL=false` and
`MINIDRIVE_REPLICATION_ENABLED=true`. Startup checks every durable version
object has a verified, healthy expectation on its recorded authoritative node.
It refuses to serve in replication mode while any expectation is missing.
Staged `_uploads/` chunk objects are not durable file versions and are excluded;
they continue to use the existing upload lifecycle.

Example from `backend` (set the normal database, JWT, and storage environment
variables for each invocation):

```powershell
$env:MINIDRIVE_REPLICATION_ENABLED = 'false'
$env:MINIDRIVE_REPLICATION_BACKFILL = 'true'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.main.web-application-type=none"
```

Stop that process after a successful report, set backfill to `false`, enable
replication, and restart. The operation is safe to rerun after interruptions.
