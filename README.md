# guacamole-otel

OpenTelemetry instrumentation for [Apache Guacamole](https://guacamole.apache.org/):
session lifecycle traces, metrics, and authentication events — as a drop-in
extension, with zero changes to Guacamole itself.

> Status: pre-release. Phases 0–2 complete and verified (API verification, the
> listener extension, the collector bundle with end-to-end correlation). See
> `docs/BUILD-PHASES.md` and `docs/VERIFIED.md`.

## What it is

- **`extension/`** — a `guacamole-ext` listener (one small jar in
  `GUACAMOLE_HOME/extensions/`) emitting:
  - one **span** per session (connect → close): protocol, connection id, user,
    datasource, client address, duration, end reason
  - **metrics**: active sessions, session starts, a session-duration histogram,
    auth attempts by outcome, and self-telemetry (errors, registry size/evictions)
  - **log records** for every authentication success/failure and session
    connect/close — attempt-level access evidence as a byproduct of normal
    operation
- **`collector/`** — stock OpenTelemetry Collector configuration: guacd and Tomcat
  logs (journald), guacd process metrics, and promotion of the Guacamole
  connection id so guacd log lines **join** the session telemetry.
- **`dashboards/`** — a Grafana starter dashboard.

It is *agent-bridge*: the extension bundles only `opentelemetry-api` and relies on
the OpenTelemetry Java agent on the servlet container for the SDK, exporters, and
configuration (all standard `OTEL_*` env). Quickstart: **`docs/INSTALL.md`**.

## What it deliberately is **not**

- **Not** a fork or patch of `guacamole-client` or `guacd`. Extension API only.
- **Not** a protocol-stream interceptor: no keystrokes, no screen data, no
  clipboard, no file content — connection **metadata only**. The instruction-level
  filter is specified but deliberately unbuilt (`docs/EXTENSION-POINTS.md`).
- **Not** policy enforcement: the listener observes and can never veto a login or
  connection. Its event handler swallows every `Throwable` — a telemetry defect
  can never become an availability incident (guardrail G1).
- **Not** a tamper-evident audit store — the telemetry is *operational* (see below).

## Reliability by construction

- **No blocking I/O on the event thread**; exports are async with bounded queues.
  Collector down ⇒ telemetry drops, logins and sessions are unaffected (verified).
- **Bounded state**: the session registry has a hard cap + a TTL sweep (G6).
- **Cardinality guard**: high-cardinality attributes (`tunnel.uuid`,
  `connection.id`, `client.address`) are unattachable to metrics at the type level
  (G4); credentials never touch telemetry (G3).

## Compliance mapping

Access evidence becomes a byproduct of normal operation rather than a manual
extraction from the Guacamole database.

| Requirement | Evidence produced |
|---|---|
| **NIS2 Art. 21** — access control, logging of privileged access | `guacamole.auth.*` log records; session records with user, target, protocol, duration |
| **DORA Art. 9/10** — ICT protection, anomaly detection | session concurrency + duration metrics; auth failure rate as a detection signal |
| **ISO 27001 A.8.15 / A.8.16** — logging, monitoring | full attempt-level auth log; session lifecycle |
| **Least-privilege review** | per-user, per-connection session records support periodic access recertification |

**Non-repudiation caveat:** this telemetry is *operational*, not tamper-evident. It
is not a substitute for a WORM audit store, and the collector pipeline is not an
evidential chain of custody. See `docs/PRIVACY.md`.

## Build

```sh
mvn -f extension/pom.xml verify        # extension jar + tests
otelcol-contrib validate --config collector/otelcol-guacamole.yaml
```

## License

Apache-2.0. Independent project, not affiliated with or endorsed by the Apache
Software Foundation.
