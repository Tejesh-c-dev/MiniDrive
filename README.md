# MiniDrive
A mini version alternative for google drive

## Local database

MiniDrive development PostgreSQL runs in Docker and is isolated from any PostgreSQL
installation on Windows. The container keeps PostgreSQL's internal port `5432`,
but publishes it only on loopback at `127.0.0.1:55432`.

From `C:\MiniDrive`, start the database with:

```powershell
docker compose -f infra/docker-compose.yml up -d
docker ps
```

Wait for `minidrive-postgres` to show `(healthy)`. Verify the database explicitly:

```powershell
$env:PGPASSWORD="minidrive"
psql -h 127.0.0.1 -p 55432 -U minidrive -d minidrive -c "SELECT 1;"
psql -h 127.0.0.1 -p 55432 -U minidrive -d minidrive -c "SELECT current_user, current_database();"
```

Run the backend from `C:\MiniDrive\backend`:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

The backend reads its datasource settings from the root `.env` file. Copy
`.env.example` to `.env` for a new checkout and keep the real `.env` local.
