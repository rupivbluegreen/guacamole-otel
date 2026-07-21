# INSTALL.md

Deploying the Guacamole OpenTelemetry extension + collector bundle.

## Deployment options

| Target | Path | Notes |
|---|---|---|
| **RHEL9 / bare-metal / VM** | RPM — `packaging/nfpm.yaml` | drop-in jar + systemd collector; steps below |
| **Docker** | `packaging/docker/` | instrumented guacamole image (extension + agent baked in) + a reference compose |
| **OpenShift / Kubernetes** | `packaging/openshift/` | manifests + kustomization; arbitrary-UID/SCC-safe; see that dir's README |

The sections below cover the RPM/manual path. For containers, the extension and
agent are **baked into the image** (`packaging/docker/Dockerfile`) so there is no
writable-mount or UID concern; you only set `OTEL_EXPORTER_OTLP_ENDPOINT` at runtime.

## Requirements

- Apache Guacamole **1.6.x** (the extension manifest declares `"*"`, so it loads
  on any version, but 1.6.x is what is tested).
- The **OpenTelemetry Java agent** on the servlet container (Tomcat). The
  extension is *agent-bridge*: it bundles only `opentelemetry-api` and relies on
  the agent to supply the SDK, exporters, and configuration. Without the agent the
  extension loads and no-ops (fail-open) — it never blocks Guacamole.
- An OTLP endpoint (the collector below, or your backend directly).

## 1. Attach the OpenTelemetry Java agent

Download `opentelemetry-javaagent.jar` and point Tomcat at it, e.g. in
`setenv.sh` or the service environment:

```sh
export JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
export OTEL_SERVICE_NAME=guacamole
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=otlp
export OTEL_LOGS_EXPORTER=otlp
```

All exporter configuration is standard `OTEL_*` — the extension inherits it. No
extension-specific exporter config exists.

## 2. Install the extension

**RPM (RHEL9):**
```sh
sudo rpm -i guacamole-otel-extension-<version>.noarch.rpm   # -> /etc/guacamole/extensions/
sudo systemctl restart tomcat                               # or your servlet container
```

**Manual:**
```sh
sudo cp guacamole-otel-extension.jar "$GUACAMOLE_HOME/extensions/"
sudo systemctl restart tomcat
```

Confirm it loaded — the Guacamole log shows:
```
Extension "OpenTelemetry Instrumentation" (otel) loaded.
```

### Optional extension settings (`guacamole.properties`)

All keys are `otel.`-prefixed, optional, with safe defaults:

| Key | Default | Meaning |
|---|---|---|
| `otel.enabled` | `true` | master switch |
| `otel.registry.max-entries` | `10000` | hard cap on in-flight sessions tracked |
| `otel.session.ttl-hours` | `24` | orphaned sessions end as `timeout` after this |
| `otel.registry.sweep-interval-minutes` | `60` | TTL sweep cadence |
| `otel.metrics.include-user` | `false` | allow `enduser.id` as a metric dimension (cardinality!) |
| `otel.attributes.hash-user` | `false` | salted-SHA256 pseudonymise `enduser.id` |
| `otel.attributes.hash-user-salt` | — | salt for the above (never logged) |

## 3. Install the collector bundle

```sh
sudo mkdir -p /etc/otelcol-guacamole
sudo cp collector/otelcol-guacamole.yaml /etc/otelcol-guacamole/
sudo cp collector/systemd/otelcol-guacamole.env /etc/otelcol-guacamole/otelcol.env
# edit otelcol.env: set OTLP_EXPORTER_ENDPOINT to your backend
sudo cp collector/systemd/otelcol-guacamole.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now otelcol-guacamole
```

The collector receives the agent/extension telemetry over OTLP on `127.0.0.1`,
scrapes guacd process metrics, reads guacd/Tomcat logs from journald, and promotes
`guacamole.connection.id` so guacd log lines join the session telemetry.

## Verify

Open a session, then check your backend for a `guacamole.session` span (on close),
a `guacamole.session.connected` log at connect, and a `guacamole.auth.success`
log at login — all sharing `enduser.id` / `guacamole.connection.id`.

## Upgrade

Replace the jar (`rpm -U …` or copy over) and restart the servlet container. No
database migration, no webapp rebuild.

## Uninstall

```sh
sudo rpm -e guacamole-otel-extension     # or: rm "$GUACAMOLE_HOME/extensions/guacamole-otel-extension.jar"
sudo systemctl restart tomcat
```

Complete removal — no residue in Guacamole's schema or config. `rpm -e` leaves
nothing behind (no scriptlets).
