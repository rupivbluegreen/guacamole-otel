# guacamole-otel

OpenTelemetry instrumentation for [Apache Guacamole](https://guacamole.apache.org/):
session lifecycle traces, metrics, and authentication events — as a drop-in
extension, with zero changes to Guacamole itself.

> Status: pre-release. Phase 0 (API verification) in progress — see
> `docs/BUILD-PHASES.md`.

## What it is

- **`extension/`** — a `guacamole-ext` listener extension (single small jar in
  `GUACAMOLE_HOME/extensions/`) emitting:
  - one span per session (connect → close), with protocol, connection,
    user, datasource, duration
  - metrics: active sessions, session starts, duration histogram, auth
    attempts by outcome
  - log records for every authentication success/failure and session
    connect/close — attempt-level access evidence (NIS2 / DORA / ISO 27001
    A.8.15/16) as a byproduct of normal operation
- **`collector/`** — stock OpenTelemetry Collector configuration: guacd and
  Tomcat logs (journald), guacd process metrics, and promotion of the
  Guacamole connection ID so guacd log lines join session telemetry.
- **`dashboards/`** — Grafana starter dashboard.

## What it deliberately is not

- Not a fork or patch of guacamole-client or guacd.
- Not a protocol-stream interceptor: no keystrokes, no screen data, no
  clipboard content — connection **metadata only**. See `docs/SPEC.md` §16
  for why instruction-level telemetry is specified but unbuilt.
- Not policy enforcement: the listener observes and can never veto a login
  or connection, by construction.
- Not a tamper-evident audit store.

## Requirements

Guacamole 1.6.x · Java 17 · an OTLP endpoint. The OpenTelemetry Java agent on
the servlet container is recommended (JVM + HTTP + JDBC telemetry for free,
and it carries the exporter configuration for the extension).

## Quickstart

See `docs/INSTALL.md` (Phase 3). Design: `docs/SPEC.md`. Privacy and GDPR
posture: `docs/PRIVACY.md`.

## License

Apache-2.0. Independent project, not affiliated with or endorsed by the
Apache Software Foundation.
