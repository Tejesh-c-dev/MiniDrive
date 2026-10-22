# Phase 12.1 and 12.2 monitoring

MiniDrive exposes `/actuator/health`, its `/actuator/health/database` and
`/actuator/health/cache` dependency groups, and `/actuator/prometheus`. All of
these management routes are allowed only to loopback clients. Health component
statuses are available without health details. Actuator shares the backend's
application port 8080; there is no separate management listener on port 8081.
The API continues to provide `/api/health`.

## Run Prometheus on Windows

Download the Windows AMD64 Prometheus release from the
[official Prometheus download page](https://prometheus.io/download/), extract
the archive somewhere outside the repository (for example,
`C:\tools\prometheus`), and use the included `prometheus.exe` and `promtool.exe`.

Start the backend first. From `C:\MiniDrive\backend`, run the existing
development workflow:

```powershell
.\mvnw.cmd spring-boot:run
```

The scrape configuration is [infra/prometheus.yml](../infra/prometheus.yml).
From a separate PowerShell window, run Prometheus from its extracted directory;
the TSDB data stays in the user's local application data directory, outside Git:

```powershell
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\MiniDrive\prometheus" | Out-Null
Set-Location C:\tools\prometheus
.\promtool.exe check config C:\MiniDrive\infra\prometheus.yml
.\prometheus.exe --config.file=C:\MiniDrive\infra\prometheus.yml --storage.tsdb.path="$env:LOCALAPPDATA\MiniDrive\prometheus" --web.listen-address=127.0.0.1:9090
```

Open the local UI at http://127.0.0.1:9090. Check **Status > Targets** for the
`minidrive` job, or query
http://127.0.0.1:9090/api/v1/targets and confirm its health is `up`. A sample
query such as `up{job="minidrive"}` should return `1`; metrics such as
`jvm_memory_used_bytes` confirm scraped samples are present.

Prometheus runs directly on the Windows host so it can reach the backend at
`127.0.0.1:8080`, which Actuator's loopback-only security rules allow. A
containerized Prometheus would have its own network namespace and could not use
that host loopback address. Prometheus itself binds its web UI to
`127.0.0.1:9090`, keeping the UI local. No separate Actuator listener or
non-loopback Actuator access is enabled.
