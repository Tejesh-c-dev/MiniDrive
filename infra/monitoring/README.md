# Local observability

This is a development setup, not a production monitoring deployment. The
backend and frontend remain host-run. The existing Compose project runs
PostgreSQL, Redis, and the two Silo storage nodes; the monitoring profile adds
PostgreSQL and Redis exporters. Prometheus and Grafana run on the host because
the backend permits `/actuator/prometheus` only to loopback clients.

## Prerequisites and start

Use Docker Desktop with Compose, the Java/Maven prerequisites in the root
README, and downloaded Prometheus and Grafana binaries. Copy `.env.example` to
`.env`, set its local development credentials, including a unique
`POSTGRES_EXPORTER_PASSWORD`, then start the application infrastructure and
backend using the root README. The frontend is optional for metrics.

Redis requires distinct `REDIS_USERNAME`/`REDIS_PASSWORD` and
`REDIS_EXPORTER_USERNAME`/`REDIS_EXPORTER_PASSWORD` values. Generate random hex
passwords and store them only in `.env`. Redis disables the default ACL user;
the application identity uses all keys in this dedicated instance for cache
and rate-limit operations. The exporter identity can inspect server metrics
and selected key types but cannot write keys or change server configuration.
Exporter auth failures leave the Prometheus target down rather than reporting
healthy Redis metrics. Redis passwords must contain only letters, digits,
underscore, or hyphen for the ACL bootstrap format.

The app user is granted `@read`, `@write`, Lua `EVAL`/`EVALSHA`, `PING`,
`HELLO`, and `CLIENT SETINFO`; `@admin` and `@dangerous` are removed. Its key
pattern is `~*` because this Redis instance is dedicated to MiniDrive. The
exporter starts from `-@all` and receives connection, `INFO`, memory, latency,
`CONFIG GET`, `SLOWLOG`, `SCAN`, and selected key inspection commands used by
the pinned exporter. It receives no write, admin, or dangerous category. An
`NOAUTH` error usually means the process did not receive credentials; `NOPERM`
identifies a missing ACL command grant. Correct the identity/secret source and
restart only the affected client. Do not enable the Redis default ACL user as
a workaround.

Provision a read-only PostgreSQL monitoring role once (uses the local
development PostgreSQL administrator only for this role setup):

```powershell
$env:POSTGRES_EXPORTER_PASSWORD = 'the same local value as in .env'
& .\infra\monitoring\setup-postgres-exporter.ps1
```

Start only the optional exporters. If the normal infrastructure services are
already up, Compose reuses them without recreating their volumes:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml --profile monitoring up -d postgres-exporter redis-exporter
```

Run Prometheus in one terminal. Set `$prometheusHome` to the extracted release
directory; the data directory is outside the repository:

```powershell
$prometheusHome = 'C:\tools\prometheus'
$repo = (Resolve-Path .).Path
$data = Join-Path $env:LOCALAPPDATA 'MiniDrive\prometheus'
New-Item -ItemType Directory -Force $data | Out-Null
& (Join-Path $prometheusHome 'promtool.exe') check config (Join-Path $repo 'infra\prometheus.yml')
& (Join-Path $prometheusHome 'prometheus.exe') --config.file=(Join-Path $repo 'infra\prometheus.yml') --storage.tsdb.path=$data --web.listen-address=127.0.0.1:9090
```

Run Grafana in another terminal. Set `$grafanaHome` to its extracted release
directory. The dashboard directory is resolved from the checkout at runtime;
Grafana's local admin password is prompted and stays in this process
environment:

```powershell
$grafanaHome = 'C:\tools\grafana'
$repo = (Resolve-Path .).Path
$env:GF_SERVER_HTTP_ADDR = '127.0.0.1'
$env:GF_SERVER_HTTP_PORT = '3000'
$env:GF_SECURITY_ADMIN_USER = 'admin'
$env:GF_SECURITY_ADMIN_PASSWORD = Read-Host 'Local Grafana admin password'
$env:GF_PATHS_PROVISIONING = Join-Path $repo 'infra\grafana\provisioning'
$env:MINIDRIVE_GRAFANA_DASHBOARDS = Join-Path $repo 'infra\grafana\dashboards'
& (Join-Path $grafanaHome 'bin\grafana-server.exe') --homepath $grafanaHome --config (Join-Path $grafanaHome 'conf\defaults.ini')
```

Open Grafana at http://127.0.0.1:3000 and select **MiniDrive Overview**. The
Prometheus UI is http://127.0.0.1:9090. Both interfaces bind to loopback. Stop
the host processes with Ctrl+C; stop only the exporters with:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml stop postgres-exporter redis-exporter
```

This does not stop PostgreSQL, Redis, Silo, or remove any volumes. Do not run
`down -v` as part of monitoring shutdown.

## Targets and actual coverage

Prometheus scrapes every 15 seconds. Backend port and path are sourced from
`server.port` (8080) and the configured Actuator endpoint (`/actuator/prometheus`).
Security remains unchanged: only health and Prometheus are exposed, hidden
health details stay hidden, and those routes are permitted only from
`127.0.0.1`. This is why Prometheus is a host process. Its UI is also loopback
only.

| Target | Source | Metrics |
|---|---|---|
| `minidrive` | Host backend `127.0.0.1:8080/actuator/prometheus` | HTTP request count and cumulative duration, JVM/GC/thread/process metrics, and implemented file, storage, rate-limit, upload-session, replication, and spool meters. Labels are bounded by operation/outcome/node categories; no user, object, file, URL, or request identifiers are added. |
| `postgres` | `postgres-exporter` on `127.0.0.1:19187` | Availability, database connections, transactions, cache block activity, and database sizes. Exporter login is `minidrive_exporter`, granted `CONNECT` and built-in `pg_monitor`; it is not the PostgreSQL administrator and has no application-table grants. |
| `redis` | `redis-exporter` on `127.0.0.1:19121` | Authenticated with the dedicated exporter ACL user; availability, uptime, memory, clients, commands, and keyspace/hit/miss metrics supported by Redis 8.2. |
| `silo-*` | Silo cluster, node, and resource endpoints on loopback API ports 9000 and 9100 | Service availability; incoming S3 requests and rejected-auth counts; usable capacity; node process CPU/resident memory; and node host-view memory/CPU/disk/network metrics. |

Silo metrics authentication is set to `public` in this development Compose
stack because Prometheus uses the local, loopback-published S3 API port. S3
credentials remain required for object operations. Do not carry this local
metrics setting into a remotely reachable deployment. Silo node resource
metrics are the Linux host view available to the Silo process (often the
shared Docker Desktop VM); they do not prove isolated per-container CPU or
memory consumption. Capacity used on the dashboard is derived from the
observed usable total minus usable free gauges. Object count/usage is not
currently exposed by the pinned single-node scrape response. No Docker socket,
privileged exporter, host rootfs mount, or node exporter is used.

PostgreSQL query-level timing and per-container CPU/memory are not collected.
The exporter reports database statistics, not query latency percentiles.
Redis metrics describe the Redis server but do not change or bypass MiniDrive's
fail-closed rate limiter. Object capacity and usage describe Silo storage, not
container resource use. Application HTTP latency is count/sum; no histogram
buckets are configured, so the dashboard shows a mean estimate and not p50 or
p95. Rate-limit events do not currently have a dedicated application meter;
the dashboard only includes rate-limit metrics if implemented in a future
change. Replication spool gauges/counters are exposed by the current backend.

## Validation and diagnosis

From the repository root:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml config --quiet
docker compose --env-file .env -f infra/docker-compose.yml --profile monitoring config --quiet
```

Run `promtool check config infra/prometheus.yml`, parse the Grafana YAML/JSON,
and validate provisioning paths using the script/checks documented in the
root README. In Prometheus, inspect **Status → Targets** or
`http://127.0.0.1:9090/api/v1/targets`; each configured target should be `UP`.
An unavailable service is intentionally `DOWN`, not masked by the application
health endpoint. Check that the backend is running on port 8080, both exporters
are running, the PostgreSQL exporter role/password match `.env`, Redis is
reachable on its Compose network, and each Silo node exposes its configured
loopback API port. A failed monitoring target does not control application
request handling or rate limiting.

The `dev` profile and root `.env` are for local development. The `prod` profile
requires externally injected credentials and a separately designed secured
network, TLS, monitoring credential lifecycle, and remote scrape policy; this
repository does not provide a production-ready monitoring stack.
