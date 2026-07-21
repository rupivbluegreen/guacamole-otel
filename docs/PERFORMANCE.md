# PERFORMANCE.md — measured overhead

Real numbers from an end-to-end run against **real Apache Guacamole 1.6.0**
(guacamole + guacd + PostgreSQL + the OpenTelemetry Java agent + the collector),
driving actual authenticated SSH sessions. Everything here is reproducible from
`itest/` — see the bottom of this doc.

## TL;DR

- **Per-event listener overhead: p99 ≈ 20 µs** (budget was < 1 ms — met with ~30–50× headroom).
- **Added login latency: none measurable** — with the extension vs agent-only, the login
  latency distributions are statistically identical (both p50 ≈ 24.5 ms, bcrypt-bound).
- **Metric cardinality is bounded** and independent of session/connection count.
- **Backend failure has zero effect** on Guacamole logins or sessions.
- **Footprint:** a 234 KB jar + a hard-bounded in-memory registry.

## Environment

| | |
|---|---|
| Guacamole / guacd | 1.6.0 (official images) |
| Runtime JVM | Eclipse Temurin 21 (guacamole image), Tomcat 9.0.106 |
| OTel Java agent | 2.29.0 |
| `opentelemetry-api` (shaded) | 1.43.0 |
| Collector | otelcol-contrib 0.156.0 |
| Host | single machine, docker-compose (`itest/`), ~8 GiB RAM |

> These are single-host, indicative numbers — not a tuned production benchmark. The
> **methodology and harness are open** (`itest/`), so you can reproduce and scale them.

## 1. Per-event listener overhead

The listener runs synchronously on Guacamole's event thread, so the number that
matters is how long `handleEvent` takes. Measured in isolation (in-memory SDK, no
network, no password hashing) over **50,000 connect + close pairs** after 20,000
warm-up iterations (`OverheadBenchmarkTest`):

| Percentile | Per-event latency |
|---|---|
| p50 | **~5 µs** |
| p99 | **~20 µs** (19–34 µs across runs) |
| p99.9 | **~50 µs** |

Budget (SPEC §13/§15.3): `< 1 ms` p99 → **met with ~30–50× headroom**. There is no
blocking I/O on the event thread; exports are async with bounded queues.

## 2. Added login latency (A/B, real Guacamole)

100 `POST /api/tokens` logins against real Guacamole, extension **active** vs an
**agent-only baseline** (identical image + agent, no extension), same PostgreSQL:

| Config | p50 | p90 | p99 | mean |
|---|---|---|---|---|
| Baseline (agent only) | 24.6 ms | 33.1 ms | 46.3 ms | 25.6 ms |
| **Extension active** | 24.5 ms | 34.5 ms | 42.9 ms | 25.9 ms |
| **Delta** | **−0.1 ms** | +1.4 ms | −3.4 ms | **+0.3 ms** |

Login latency is dominated by bcrypt password hashing + the DB round-trip (~25 ms).
The extension's contribution (~20 µs, §1) is **below the run-to-run noise floor** — the
distributions are statistically indistinguishable (the extension is even faster at
p99 here, which is noise, not a real gain).

## 3. Metric cardinality (bounded by design)

Metric dimensions are restricted at the type level (guardrail G4): high-cardinality
attributes (`tunnel.uuid`, `connection.id`, `client.address`) **cannot** be attached
to metrics. Driving many sessions, the session metrics stayed a **single series**:

```
guacamole.session.started{guacamole.protocol=ssh, guacamole.datasource=postgresql} = <count>
```

Series count is a function of `protocols × datasources` (× outcome/reason for a few
instruments) — **independent of the number of sessions or connections**. This is the
single most important property for not destroying a metrics backend.

## 4. Resource footprint

| | |
|---|---|
| Extension jar | **234 KB** (agent-bridge: only `opentelemetry-api` + `context` shaded) |
| In-memory state | one `SessionState` per in-flight session in a **hard-bounded** registry (default cap 10,000 entries, 24 h TTL sweep) — cannot grow without bound under churn (G6) |
| Guacamole container RSS | ~361 MiB total (Tomcat + agent + webapp + extension) |

Restart-lossy by design: no persistence, no disk growth.

## 5. Resilience under backend failure

Telemetry failures must never become availability incidents. Verified (see
`docs/VERIFIED.md` Gate P2/P4):

| Scenario | Login | Session | Guacamole |
|---|---|---|---|
| Collector killed **mid-session** | ✅ ~115 ms, unaffected | ✅ SSH established | ✅ healthy, no OOM |
| Collector **down from start** | ✅ succeeds | ✅ SSH established | ✅ extension loads, no errors |
| Exporter **throws on every call** | ✅ (`handleEvent` returns normally, unit-tested) | ✅ | ✅ |

The agent/SDK exporters are async with bounded queues — under backpressure they
**drop, never block**.

## Reproduce

```sh
# build the extension jar
docker run --rm -v "$PWD/extension":/w -w /w maven:3.9-eclipse-temurin-17 mvn -q -B package

# per-event micro-benchmark
docker run --rm -v "$PWD/extension":/w -w /w maven:3.9-eclipse-temurin-17 \
  mvn -q -B test -Dtest=OverheadBenchmarkTest   # prints p50/p99/p99.9

# end-to-end correlation + failure injection against real Guacamole
cd itest && ./run-correlation-test.sh
```

Login-latency A/B: run 100 `POST /guacamole/api/tokens` against the extension stack
(`:18080`) and an agent-only container, and compare `curl -w '%{time_total}'`
distributions.
