# Disposable PostgreSQL and Silo environment

This environment reuses the pinned PostgreSQL 16 and `pgsty/silo:RELEASE.2026-09-16T00-00-00Z` images from `docker-compose.yml`, and includes a dedicated Redis 7.4.2 test service. It has its own Compose project, PostgreSQL instance/database, credentials, Silo instances, named volumes, and loopback ports. Redis listens on 127.0.0.1:56439 with persistence disabled and no data volume. It does not attach to the development Compose network or volumes or affect Redis on port 56379.

From the repository root in PowerShell:

```powershell
./infra/live-test-up.ps1
docker compose --project-directory . --project-name (Get-Content infra/.live-test-project -Raw) -f infra/live-test-compose.yml ps
```

Use these explicit settings for the application and opt-in test classes:

```text
REDIS_HOST=127.0.0.1
REDIS_PORT=56439
DB_URL=jdbc:postgresql://127.0.0.1:55439/minidrive_it
DB_USERNAME=md_it
DB_PASSWORD=md_it_2026_disposable
MINIO_ENDPOINT=http://127.0.0.1:9200
MINIO_ACCESS_KEY=md_it_silo
MINIO_SECRET_KEY=md_it_silo_2026_disposable
MINIO_BUCKET=minidrive-it-primary
MINIO_SECONDARY_ENDPOINT=http://127.0.0.1:9300
MINIO_SECONDARY_ACCESS_KEY=md_it_silo
MINIO_SECONDARY_SECRET_KEY=md_it_silo_2026_disposable
MINIO_SECONDARY_BUCKET=minidrive-it-secondary
```

Run the live profile from `backend` only after setting those values in the current process. The migration test classes additionally require their `minidrive.live-upload-cleanup.admin-url` (or `minidrive.live-pg-reconciliation.admin-url`) to point to `jdbc:postgresql://127.0.0.1:55439/postgres`, and the matching disposable credentials. Never set the development `.env` values for live tests.

Inspect resources with `docker compose ... ps` and `docker volume ls --filter label=com.docker.compose.project=<project>`. The startup script records a unique project identity in `infra/.live-test-project`; teardown validates that identity on every remaining container before removing only that Compose project and its named volumes:

```powershell
./infra/live-test-down.ps1
```

Do not manually use broad `docker compose down`, `docker volume prune`, or development Compose commands for this environment. If startup fails, its error path tears down only its newly generated project.

The MD-015 opt-in upload check is `mvn '-Dminidrive.live-pg-minio=true' '-Dtest=ReplicationSpoolRealInfrastructureTest' test` from `backend`. It first runs the integrity backfill against this disposable database, then verifies a bounded 128 KiB upload on both isolated Silo nodes and cleans the generated object and its spool.
