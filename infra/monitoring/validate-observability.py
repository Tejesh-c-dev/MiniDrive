"""Static checks for MiniDrive's local observability contract."""

import json
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]


def load_yaml(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


prom = load_yaml(ROOT / "infra/prometheus.yml")
app = load_yaml(ROOT / "backend/src/main/resources/application.yml")
compose = load_yaml(ROOT / "infra/docker-compose.yml")
provider = load_yaml(ROOT / "infra/grafana/provisioning/dashboards/minidrive.yml")
datasource = load_yaml(ROOT / "infra/grafana/provisioning/datasources/prometheus.yml")
dashboard = json.loads((ROOT / "infra/grafana/dashboards/minidrive-overview.json").read_text(encoding="utf-8"))
security = (ROOT / "backend/src/main/java/com/minidrive/config/SecurityConfig.java").read_text(encoding="utf-8")

app_port = app["server"]["port"]
actuator_paths = app["management"]["endpoints"]["web"]["exposure"]["include"]
actuator_base = app["management"]["endpoints"]["web"].get("base-path", "actuator")
scrapes = {job["job_name"]: job for job in prom["scrape_configs"]}
assert prom["global"]["scrape_interval"] == "15s"
assert prom["global"]["evaluation_interval"] == "30s"
assert scrapes["minidrive"]["metrics_path"] == f"/{actuator_base}/prometheus"
assert scrapes["minidrive"]["static_configs"][0]["targets"] == [f"127.0.0.1:{app_port}"]
assert set(actuator_paths) == {"health", "prometheus"}
assert "IpAddressMatcher(\"127.0.0.1\")" in security and ".denyAll()" in security

expected_targets = {
    "postgres": ("127.0.0.1:19187", None),
    "redis": ("127.0.0.1:19121", None),
    "silo-primary": ("127.0.0.1:9000", "/minio/v2/metrics/cluster"),
    "silo-primary-node": ("127.0.0.1:9000", "/minio/v2/metrics/node"),
    "silo-primary-resource": ("127.0.0.1:9000", "/minio/v2/metrics/resource"),
    "silo-secondary": ("127.0.0.1:9100", "/minio/v2/metrics/cluster"),
    "silo-secondary-node": ("127.0.0.1:9100", "/minio/v2/metrics/node"),
    "silo-secondary-resource": ("127.0.0.1:9100", "/minio/v2/metrics/resource"),
}
for job_name, (target, path) in expected_targets.items():
    job = scrapes[job_name]
    assert job["static_configs"][0]["targets"] == [target], job_name
    if path:
        assert job["metrics_path"] == path, job_name

services = compose["services"]
assert services["postgres-exporter"]["profiles"] == ["monitoring"]
assert services["redis-exporter"]["profiles"] == ["monitoring"]
assert services["postgres-exporter"]["environment"]["DATA_SOURCE_USER"] == "minidrive_exporter"
for name, target in (("postgres-exporter", "19187"), ("redis-exporter", "19121")):
    port = services[name]["ports"][0]
    assert port.startswith(f"127.0.0.1:{target}:") , (name, port)

def published_port(service, container_port):
    prefix = f"127.0.0.1:"
    suffix = f":{container_port}"
    return next(int(port[len(prefix):-len(suffix)]) for port in service["ports"]
                if port.startswith(prefix) and port.endswith(suffix))


assert published_port(services["minio"], 9000) == 9000
assert published_port(services["minio-secondary"], 9000) == 9100
assert services["minio"]["environment"]["MINIO_PROMETHEUS_AUTH_TYPE"] == "public"
assert services["minio-secondary"]["environment"]["MINIO_PROMETHEUS_AUTH_TYPE"] == "public"
assert "${MINIDRIVE_GRAFANA_DASHBOARDS}" in str(provider)
assert datasource["datasources"][0]["uid"] == "minidrive-prometheus"
assert datasource["datasources"][0]["url"] == "http://127.0.0.1:9090"
assert all(panel["datasource"]["uid"] == "minidrive-prometheus" for panel in dashboard["panels"])
assert all(panel.get("targets") for panel in dashboard["panels"])
assert "C:/" not in str(provider) and "C:\\" not in str(provider)

print(f"observability static checks: PASS ({len(scrapes)} scrape jobs, {len(dashboard['panels'])} dashboard panels)")
