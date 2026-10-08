# MiniDrive
A mini version alternative for google drive

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

The backend reads its datasource settings from the root `.env` file. Copy
`.env.example` to `.env` for a new checkout and keep the real `.env` local.

## Object storage (MinIO)

MiniDrive stores file objects in MinIO. Because MinIO stopped publishing its
community Docker images in 2026, the stack uses the community-maintained
[Silo fork](https://github.com/pgsty/minio), a drop-in MinIO replacement with
security patches and the full admin console.

The `infra/docker-compose.yml` stack now starts three containers:

- `minidrive-postgres` - PostgreSQL on `127.0.0.1:55432`
- `minidrive-minio` - MinIO API on `127.0.0.1:9000`, console on `127.0.0.1:9001`
- `minidrive-minio-init` - a one-shot bootstrap that waits for MinIO, creates the
  private `minidrive-files` bucket and exits. It is idempotent, so re-running
  `docker compose up` never fails because the bucket already exists.

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

The bucket is private: anonymous access is disabled, so objects are not publicly
readable or writable. Override the credentials, endpoint and bucket through the
root `.env` file (see `.env.example`); the backend reads `MINIO_ENDPOINT`,
`MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` and `MINIO_BUCKET`.
