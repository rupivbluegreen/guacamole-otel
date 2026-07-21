# guacamole-otel

**See who connects to what, for how long, and whether logins succeed — for [Apache Guacamole](https://guacamole.apache.org/), with zero changes to Guacamole itself.**

Guacamole is a great clientless remote-desktop gateway, but out of the box it tells
you almost nothing about what's happening inside it: no metrics, no traces, no
easy access log. This project fixes that by adding standard
[OpenTelemetry](https://opentelemetry.io/) instrumentation — so your existing
observability backend (Grafana, Tempo/Loki, Elastic, SigNoz, Datadog, …) can show
Guacamole activity like any other service.

> **Status:** v0.1.0 — the extension, the collector bundle, and packaging for
> RHEL9 / Docker / OpenShift are complete and verified. Apache-2.0.

---

## What you get

- 🔎 **A record of every session** — who connected, to which target, over which
  protocol (RDP/VNC/SSH/…), from which IP, and for how long.
- 📊 **Live metrics** — active sessions, session rate, a duration histogram, and
  authentication attempts by outcome. Ready-made Grafana dashboard included.
- 🔐 **An access log for free** — every login success/failure and session
  start/stop as a structured event. Useful evidence for NIS2 / DORA / ISO 27001
  audits, produced automatically.
- 🔗 **Correlation with guacd** — the low-level `guacd` daemon's logs are joined
  to your session data automatically, so a log line and a session share one id.

## What it deliberately does **not** do

- **It never watches your screen or keystrokes.** Only connection *metadata* is
  collected — no screen contents, no keystrokes, no clipboard, no files.
- **It can never break Guacamole.** The code that reacts to events swallows every
  error; a bug in the telemetry can never block a login or a connection.
- **It doesn't change Guacamole.** No fork, no patches — just a small plugin jar
  and stock OpenTelemetry components.
- **It's not tamper-proof audit storage.** The data is operational telemetry, not
  a legal chain of custody. (See [`docs/PRIVACY.md`](docs/PRIVACY.md).)

## How it works

Three layers, only **one** of which is our code:

```
  Browser ──► Guacamole (Tomcat) ──► guacd ──► RDP/VNC/SSH target
                  │  │
                  │  └─ [our plugin]  emits a span + metrics + an access log per session
                  └──── OpenTelemetry Java agent (JVM/HTTP/DB telemetry, for free)
                          │
                          ▼  OTLP
                  OpenTelemetry Collector  ──►  your backend (Grafana / Elastic / …)
                  (joins guacd logs to sessions)
```

Our plugin listens to Guacamole's built-in event hooks and reports what it sees.
The OpenTelemetry Java agent (which you're probably already able to add to Tomcat)
provides the plumbing; our plugin just rides on it.

## Quickstart (Docker)

The fastest way to try it — a Guacamole image with the plugin + agent baked in:

```sh
# 1. build the instrumented images
docker build -f packaging/docker/Dockerfile           -t guacamole-otel:0.1.0    .
docker build -f packaging/docker/Dockerfile.collector -t guacamole-otelcol:0.1.0 .

# 2. point the collector at your OTLP backend, then bring it up
export OTLP_EXPORTER_ENDPOINT=your-backend:4317
docker compose -f packaging/docker/docker-compose.yml up -d
```

Open a session, and it shows up in your backend. Full instructions —
including bare-metal RPM and Kubernetes/OpenShift — are in
**[`docs/INSTALL.md`](docs/INSTALL.md)**.

## Install options

| Where | How | Guide |
|---|---|---|
| **RHEL9 / VM / bare metal** | RPM (drop-in jar) + systemd collector | [`docs/INSTALL.md`](docs/INSTALL.md) |
| **Docker** | pre-built instrumented image | [`packaging/docker/`](packaging/docker/) |
| **OpenShift / Kubernetes** | manifests (SCC / arbitrary-UID safe) | [`packaging/openshift/`](packaging/openshift/) |

## Compliance, briefly

Access evidence becomes a byproduct of normal operation instead of a manual export
from the database:

| Requirement | What this gives you |
|---|---|
| **NIS2 Art. 21** — logging of privileged access | per-attempt auth records + session records (user, target, protocol, duration) |
| **DORA Art. 9/10** — anomaly detection | session concurrency/duration metrics; auth-failure rate |
| **ISO 27001 A.8.15/8.16** — logging & monitoring | full attempt-level auth log + session lifecycle |

Privacy details and pseudonymisation: [`docs/PRIVACY.md`](docs/PRIVACY.md).

## Repository layout

| Path | What |
|---|---|
| `extension/` | the Java plugin (guacamole-ext listener) + tests |
| `collector/` | OpenTelemetry Collector config + systemd unit |
| `packaging/` | RPM (nfpm), Docker, OpenShift/Kubernetes |
| `dashboards/` | Grafana starter dashboard |
| `itest/` | docker-compose integration test (correlation + failure injection) |
| `docs/` | design (`SPEC.md`), install, privacy, and the verification log (`VERIFIED.md`) |

## Building from source

```sh
mvn -f extension/pom.xml verify                                   # plugin + tests
otelcol-contrib validate --config collector/otelcol-guacamole.yaml # collector config
```

## License

[Apache-2.0](LICENSE). Independent project — not affiliated with or endorsed by
the Apache Software Foundation or the OpenTelemetry project.
