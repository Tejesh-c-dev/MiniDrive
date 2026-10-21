# Phase 12.3 Grafana monitoring

Grafana is provisioned from the repository configuration in
[`infra/grafana`](../infra/grafana): it creates the Prometheus data source at
`http://127.0.0.1:9090` and loads the MiniDrive Overview dashboard at startup.
The dashboard uses only metrics observed in the Prometheus metric-name API:
`up`, JVM memory and thread metrics, process uptime and CPU, and
`http_server_requests_seconds_count`. Its HTTP status panel groups the request
counter by the Micrometer `status` label.

## Install and run Grafana on Windows

Install the current stable Windows release using the official
[Grafana download page](https://grafana.com/grafana/download), and extract it
outside this repository (for example, `C:\tools\grafana`). The dashboard and
provisioning files remain in this repository; Grafana's database, logs, and
plugins stay under `%LOCALAPPDATA%\MiniDrive\grafana`.

Start the backend and Prometheus as described in
[`phase-12.1-monitoring.md`](phase-12.1-monitoring.md). Then run this in
PowerShell, adjusting `$grafanaHome` to the extracted directory:

```powershell
$grafanaHome = 'C:\tools\grafana'
$dataRoot = Join-Path $env:LOCALAPPDATA 'MiniDrive\grafana'
New-Item -ItemType Directory -Force $dataRoot | Out-Null
$env:GF_SERVER_HTTP_ADDR = '127.0.0.1'
$env:GF_SERVER_HTTP_PORT = '3000'
$env:GF_PATHS_DATA = Join-Path $dataRoot 'data'
$env:GF_PATHS_LOGS = Join-Path $dataRoot 'logs'
$env:GF_PATHS_PLUGINS = Join-Path $dataRoot 'plugins'
$env:GF_PATHS_PROVISIONING = 'C:\MiniDrive\infra\grafana\provisioning'
New-Item -ItemType Directory -Force $env:GF_PATHS_DATA,$env:GF_PATHS_LOGS,$env:GF_PATHS_PLUGINS | Out-Null
& (Join-Path $grafanaHome 'bin\grafana.exe') server `
  --homepath $grafanaHome `
  --config (Join-Path $grafanaHome 'conf\defaults.ini')
```

Grafana listens only on `127.0.0.1:3000`. Its initial local administrator
login is `admin` / `admin`; change the password immediately on first use
(Grafana prompts for this on first login). Do not put the changed password in
repository files or command history. Open
http://127.0.0.1:3000, then select the provisioned **MiniDrive Overview**
dashboard. The provisioned Prometheus data source should report **Healthy**.

Prometheus remains on `127.0.0.1:9090`, and MiniDrive's loopback-only Actuator
security remains unchanged. No metric labels containing user or file identity
are used by this dashboard.
