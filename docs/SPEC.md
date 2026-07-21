# Apache Guacamole — OpenTelemetry Instrumentation

**Engineering specification, v1**
Status: draft, pending Phase 0 verification
Target: Guacamole 1.6.x, OpenTelemetry Java 1.4x, RHEL9

---

## 1. Purpose and scope

Apache Guacamole emits no OpenTelemetry data and exposes no scrapeable metrics endpoint in either the web application or `guacd`. The only public prior art is `tschoonj/guacamole_exporter` — a Go binary polling the undocumented REST API for four gauges, effectively unmaintained since 2021. There is no listener extension, no collector receiver, and no registry entry.

This specification defines a three-layer instrumentation stack in which exactly one component is novel code:

| Layer | Component | Novel code |
|---|---|---|
| L1 | OTel Java agent on the servlet container | none — stock `-javaagent` |
| L2 | **`guacamole-otel-extension`** — `guacamole-ext` listener | **yes, ~1 class** |
| L3 | OTel Collector — host, log, process receivers | none — stock config |

**Design principle (inherited):** no novel code in the data path. The listener runs on event hooks in the control plane. Instruction-level interception, which would sit in the data path, is specified as an extension point in §16 and deliberately left unbuilt.

**Deliverables for v1:**
1. `guacamole-otel-extension` — drop-in `.jar` for `GUACAMOLE_HOME/extensions/`
2. Collector configuration bundle — receivers and processors for guacd/Tomcat logs and process metrics
3. Installation runbook + Grafana starter dashboard

---

## 2. Non-goals

- **No fork or patch of `guacamole-client`.** Extension API only.
- **No patch of `guacd`.** C daemon, no hook points, data path. Upstream contribution is a separate long-horizon effort (§17).
- **No keystroke, screen, or clipboard content capture.** Session recording already exists in Guacamole for forensic replay and is out of scope here. Telemetry carries connection metadata only.
- **No replacement for `guacamole_exporter`.** Optionally scraped if already deployed (§12.4); not a dependency.
- **No collector `connector` component.** Terminology note: in Collector parlance a *connector* bridges pipelines (`spanmetrics`, `count`). Nothing here is a connector. If pipeline-level derivation is wanted later, `spanmetrics` over the session spans is the stock answer.

---

## 3. Architecture

```
┌──────────────────────────── Guacamole host ───────────────────────────┐
│                                                                       │
│  ┌─── Tomcat JVM ──────────────────────────────────┐                  │
│  │  -javaagent:opentelemetry-javaagent.jar     [L1]│                  │
│  │    ├─ servlet / JAX-RS / JDBC / WebSocket spans  │                  │
│  │    └─ JVM runtime metrics                       │                  │
│  │                                                 │                  │
│  │  guacamole.war                                  │                  │
│  │    └─ extensions/guacamole-otel-*.jar       [L2]│                  │
│  │         Listener → GlobalOpenTelemetry          │                  │
│  └───────────────────┬─────────────────────────────┘                  │
│                      │ OTLP/gRPC :4317                                │
│  ┌─── guacd ─────┐   │                                                │
│  │ stdout/journal│   │                                                │
│  └───────┬───────┘   │                                                │
│          │ journald  │                                                │
│  ┌───────▼───────────▼──────── otelcol ───────────────────────────┐   │
│  │  otlp · journald · filelog · hostmetrics · [prometheus]    [L3]│   │
│  │  transform (connection-id extraction) · attributes (hashing)   │   │
│  └──────────────────────────┬─────────────────────────────────────┘   │
└─────────────────────────────┼─────────────────────────────────────────┘
                              │ OTLP
                         backend (Tempo/Loki/Mimir, Elastic, SigNoz…)
```

**Correlation key.** The Guacamole connection identifier appears in both the listener's session telemetry and guacd's log lines. The collector promotes it to an attribute (§12.2), joining L2 and L3 without shared code between them.

---

## 4. Phase 0 — verification gates

No implementation begins until all four gates pass. Each isolates one failure class. Gates 0.1 and 0.2 are blocking; 0.3 and 0.4 shape the schema.

### Gate 0.1 — `GlobalOpenTelemetry` visibility from an extension classloader
**Question:** does `GlobalOpenTelemetry.get()`, called from a class loaded by Guacamole's isolated extension classloader, return the agent's configured SDK rather than a no-op?

**Rationale:** the agent injects the OTel API into the bootstrap classloader, so it *should* be visible to all child loaders regardless of isolation. Guacamole loads extensions in a dedicated `ClassLoader` to prevent extension jars from colliding. Unverified interaction.

**Test:** minimal listener logging `GlobalOpenTelemetry.get().getClass().getName()` on `AuthenticationSuccessEvent`. Pass = an SDK implementation class, not `DefaultOpenTelemetry`/no-op.

**Branch:**
- **Pass** → agent-bridge. **RESOLVED 2026-07-21 (VERIFIED.md): PASS, but with a
  corrected dependency scope.** The agent shades its own API to
  `io.opentelemetry.javaagent.shaded.*` and does NOT expose the unshaded
  `io.opentelemetry.api.*` to the extension classloader — `provided` scope yields
  `NoClassDefFoundError`. Working pattern: **bundle (shade) `opentelemetry-api` +
  `opentelemetry-context` into the extension jar** (compile scope). The agent then
  bridges `GlobalOpenTelemetry` to its configured SDK, so the SDK, exporters, and all
  `OTEL_*` config still come from the agent — single config surface preserved. Jar ≈
  a few-hundred KB (not the SDK, not the exporters). *Chosen branch.*
- **Fail** → bundle `opentelemetry-sdk` + OTLP exporter shaded into the extension jar; extension reads its own config from `guacamole.properties`. Doubles config surface, adds ~4 MB to the jar, risks duplicate SDK initialisation. **Not needed** — the corrected Pass branch works.

### Gate 0.2 — event type inventory
**Question:** what is the exact set of event classes delivered to `Listener.handleEvent` in 1.6.x, and what accessors does each expose?

Confirmed by documentation: `AuthenticationSuccessEvent`, `AuthenticationFailureEvent`, `TunnelConnectEvent`, `TunnelCloseEvent`. **VERIFY** against `guacamole-ext` 1.6.0 javadoc: presence and signatures of any additional event types (application lifecycle, credential-request, object-modification events), and whether `TunnelCloseEvent` exposes a close reason or only the tunnel. The span/metric model in §7–8 assumes connect/close pairing only; anything richer is upside.

### Gate 0.3 — tunnel identity and connection metadata
**Question:** from `TunnelConnectEvent`, what identifies the *connection* (not the tunnel), and is the protocol reachable?

`GuacamoleTunnel.getUUID()` gives a per-tunnel UUID — unique per session, unsuitable as a metric attribute (unbounded cardinality), required as a span attribute and as the session-map key. The stable connection identifier and the protocol (`rdp`/`vnc`/`ssh`/`telnet`/`kubernetes`) must be resolved via `UserContext` → connection directory, or from `GuacamoleConfiguration` if reachable from the tunnel. **VERIFY** the exact traversal; it determines whether §8's `guacamole.protocol` dimension is available at zero cost or requires a directory lookup on the event thread (which would then need caching — see §13.4).

### Gate 0.4 — guacd log format
**Question:** exact format of guacd log lines under systemd, and whether connection ID is present at default log level.

**VERIFY** by capture. Determines the `filelog`/`transform` regex in §12.2 and whether Gap 2 correlation requires raising guacd to debug (loud) or works at info (preferred).

---

## 5. Repository layout

```
guacamole-otel/
├── LICENSE                          # Apache-2.0
├── NOTICE
├── README.md
├── CLAUDE.md                        # build guardrails for agentic sessions
├── extension/
│   ├── pom.xml
│   └── src/
│       ├── main/java/org/../otel/
│       │   ├── OtelListener.java            # entry point, dispatch, isolation
│       │   ├── SessionRegistry.java         # bounded tunnel→session map
│       │   ├── SessionSpans.java            # span lifecycle
│       │   ├── Instruments.java             # metric instrument singletons
│       │   ├── AuthEvents.java              # log-record emission
│       │   ├── Attributes.java              # attribute keys, cardinality guard
│       │   └── Config.java                  # guacamole.properties reader
│       ├── main/resources/
│       │   └── guac-manifest.json
│       └── test/java/…
├── collector/
│   ├── otelcol-guacamole.yaml
│   └── systemd/
├── dashboards/
│   └── guacamole-overview.json
└── docs/
    ├── INSTALL.md
    ├── PRIVACY.md               # DPIA input, attribute inventory
    └── EXTENSION-POINTS.md      # §16, the unbuilt instruction filter
```

---

## 6. Listener extension design

### 6.1 Manifest

```json
{
  "guacamoleVersion": "*",
  "name": "OpenTelemetry Instrumentation",
  "namespace": "otel",
  "listeners": [
    "org.apache.guacamole.otel.OtelListener"
  ]
}
```

Extension jar is dropped in `GUACAMOLE_HOME/extensions/`. Loaded on servlet container start; listeners are notified in manifest declaration order.

**VERIFIED 2026-07-21:** `guacamoleVersion` is matched against a hardcoded
allow-list in `ExtensionModule` (exact string `contains`, not a glob) — legal
values are `"*"` or an exact release string (`1.0.0`…`1.6.0`). `"1.6.*"` is
**rejected** (`"…is not compatible with this version of Guacamole"`). We ship `"*"`
(version-agnostic, no per-release bump). `listeners` is a JSON array of
fully-qualified `Listener` class names (field-name key, confirmed against 1.6.0
`ExtensionManifest`).

### 6.2 Entry point contract

```java
public class OtelListener implements Listener {

    @Override
    public void handleEvent(Object event) {
        try {
            if      (event instanceof TunnelConnectEvent e)         onConnect(e);
            else if (event instanceof TunnelCloseEvent e)           onClose(e);
            else if (event instanceof AuthenticationSuccessEvent e) onAuthSuccess(e);
            else if (event instanceof AuthenticationFailureEvent e) onAuthFailure(e);
        }
        catch (Throwable t) {                 // total isolation — see §13.1
            failureCounter.add(1);
            logger.debug("otel listener suppressed error", t);
        }
    }
}
```

**Hard invariant:** `handleEvent` never propagates. Throwing from a listener vetoes the action in progress — an exception on `AuthenticationSuccessEvent` denies the login, an exception on `TunnelConnectEvent` denies the connection. A telemetry defect must not become an availability incident. `Throwable`, not `Exception`: `NoClassDefFoundError` from a classloader mismatch is the realistic failure and it must be swallowed too.

**Corollary:** the extension must never be used to enforce policy. It observes. Any veto behaviour belongs in a separate extension with its own review.

### 6.3 Session registry

Connect and close arrive as independent events; correlating them requires state.

```
SessionRegistry: Map<UUID tunnelId, SessionState>
SessionState:    { Span span, long startNanos, Attributes dims }
```

Requirements:
- **Bounded.** Hard max entries (default 10 000, configurable). At capacity, evict oldest and increment `guacamole.otel.registry.evictions`. An unbounded map is an OOM vector in Tomcat.
- **TTL.** Entries older than `session.max_duration` (default 24 h) are evicted by a scheduled sweep; span ended with status `UNSET` and attribute `guacamole.session.end_reason=timeout`. Abnormal closes — webapp kill, network partition, guacd crash — may never deliver `TunnelCloseEvent`.
- **Concurrent.** `ConcurrentHashMap`; events arrive on request threads.
- **Restart-lossy by design.** In-memory only. On webapp restart, in-flight sessions lose their spans. Documented, not solved — persisting span context across restarts is disproportionate.

---

## 7. Telemetry schema — traces

### 7.1 Session span

| Field | Value |
|---|---|
| Name | `guacamole.session` |
| Kind | `SERVER` |
| Start | `TunnelConnectEvent` |
| End | `TunnelCloseEvent`, or TTL sweep |
| Status | `OK` on close; `UNSET` on TTL timeout. **VERIFIED: `TunnelCloseEvent` carries no close reason** — there is no failure to map to `ERROR`. |

Attributes:

| Key | Example | Notes |
|---|---|---|
| `guacamole.tunnel.uuid` | `4f2c…` | span only, never a metric dimension |
| `guacamole.connection.id` | `12` | correlation key to guacd logs |
| `guacamole.connection.name` | `prod-jump-01` | **VERIFIED not reachable** from `TunnelConnectEvent` (no `UserContext`); dropped from v1 or resolved out-of-band |
| `guacamole.protocol` | `rdp` | **VERIFIED**: `((ConfiguredGuacamoleSocket) tunnel.getSocket()).getProtocol()`, zero-cost, no lookup |
| `guacamole.datasource` | `postgresql` | |
| `enduser.id` | `a.kumar` | pseudonymisation hook, §10.2 |
| `client.address` | `10.4.2.9` | `Credentials.getRemoteAddress()` |
| `guacamole.session.end_reason` | `closed` \| `timeout` | **VERIFIED**: no `error` — close carries no reason |

### 7.2 Long-span problem — read before implementing

A span is exported only when it ends. A Guacamole session lasting eight hours produces a span that reaches the backend eight hours after it started. Sessions never closed produce no span at all until the TTL sweep. **Session spans are therefore useless as a real-time signal** and must not be the basis of alerting or live dashboards.

Consequences, all mandatory:
- Real-time signal comes from **metrics and log events emitted at connect time** (§8, §9), not from spans.
- Dashboards for "sessions active now" read `guacamole.session.active`, never span counts.
- The TTL sweep exists partly so that abandoned sessions eventually surface rather than vanishing.
- If per-session real-time visibility is later required, the answer is span *events* on a short parent plus periodic heartbeat metrics — not longer spans.

### 7.3 Trace context

The session span is a root span. Linking it to the L1 agent's WebSocket-upgrade span is desirable but requires access to the originating request context from inside the listener — **VERIFY** whether `Context.current()` at event time still carries the agent's server span. If yes, the session span becomes a child and the whole flow joins one trace. If no, add a span *link* using the connection ID as a soft join, or accept root-span isolation. Not a v1 blocker.

---

## 8. Telemetry schema — metrics

Instrument namespace `guacamole.*`. Custom rather than mapped onto `rpc.*`/`http.*` semconv: a remote-desktop session is not an RPC and forcing it into those conventions produces misleading dashboards. `enduser.id`/`client.address` follow semconv where they exist.

| Instrument | Type | Unit | Dimensions |
|---|---|---|---|
| `guacamole.session.active` | UpDownCounter | `{session}` | protocol, datasource |
| `guacamole.session.started` | Counter | `{session}` | protocol, datasource |
| `guacamole.session.duration` | Histogram | `s` | protocol, datasource, end_reason |
| `guacamole.auth.attempts` | Counter | `{attempt}` | outcome, datasource |
| `guacamole.otel.errors` | Counter | `{error}` | stage |
| `guacamole.otel.registry.size` | UpDownCounter | `{entry}` | — |
| `guacamole.otel.registry.evictions` | Counter | `{entry}` | reason |

### 8.1 Cardinality discipline — enforced in code

`Attributes.java` exposes a single builder for metric dimensions and **physically cannot** attach the high-cardinality keys. Prohibited as metric dimensions:

- `guacamole.tunnel.uuid` — unbounded, one per session
- `guacamole.connection.name` — unbounded in large estates
- `client.address` — unbounded
- `enduser.id` — bounded by user count, but that is thousands; **opt-in only**, via `otel.metrics.include-user=true`, off by default

Per-user session counts are answered from spans or logs, not from metric dimensions. This is the single most common way self-built instrumentation destroys a metrics backend; the guard is a code-level invariant with a unit test, not a convention.

### 8.2 Histogram buckets

Default OTel buckets are tuned for sub-second HTTP latency and are useless for sessions measured in minutes to hours. Explicit boundaries (seconds):

```
10, 60, 300, 900, 1800, 3600, 7200, 14400, 28800, 86400
```

---

## 9. Telemetry schema — logs / events

Authentication outcomes are emitted as OTel log records, not metrics-only, because the compliance requirement is per-attempt evidence, not aggregates (§11).

| Field | `auth.success` | `auth.failure` |
|---|---|---|
| Severity | INFO | WARN |
| `event.name` | `guacamole.auth.success` | `guacamole.auth.failure` |
| `enduser.id` | username | attempted username |
| `client.address` | remote address | remote address |
| `guacamole.datasource` | resolved datasource | — |
| Body | fixed string | fixed string |

**Never serialised, under any configuration:** the `Credentials` object, `getPassword()`, session tokens, request headers, or any field not enumerated above. `Attributes.java` builds auth records from an explicit allowlist; there is no generic object-to-attributes path in this codebase.

**VERIFIED noise filters (mandatory, §9):**
- `AuthenticationSuccessEvent` also fires on periodic token **re-authentication**
  (`isExistingSession()==true`) every few minutes per live session. Emit
  `auth.success` only for `isExistingSession()==false`, or tag
  `guacamole.auth.existing_session` and let the backend filter. Untagged, a live
  estate floods `auth.success`.
- `AuthenticationFailureEvent` fires for the initial **credential-less anonymous**
  hit that renders the login screen (`Credentials.isEmpty()==true`). Skip empty-
  credential failures or they dominate `auth.failure`.
- `AuthenticationFailureEvent.getFailure()` (nullable `Throwable`) is available — its
  class name (not message) may tag a failure reason; never serialise the message.

Session connect/close are also emitted as log records (`guacamole.session.connected` / `.closed`) carrying the span's attribute set. This is the real-time counterpart to §7.2's deferred spans, and the artefact most auditors actually want.

---

## 10. Privacy, PII, and GDPR

### 10.1 Data inventory
Telemetry carries connection *metadata* only: who connected, to which named connection, over which protocol, from which address, for how long, and whether authentication succeeded. It carries **no session content** — no keystrokes, no screen data, no clipboard, no transferred files. This is the direct consequence of the §2 non-goal and the §16 deferral, and it is the single most important sentence in the DPIA.

### 10.2 Pseudonymisation
`enduser.id` and `client.address` are personal data under GDPR. Two hooks:

1. **Extension-level:** `otel.attributes.hash-user=true` replaces `enduser.id` with a salted SHA-256 digest; salt from `guacamole.properties`, never logged. Same user hashes stably, so per-user analysis survives.
2. **Collector-level (preferred):** `transform`/`redaction` processor applies hashing or drops attributes before export. Keeps the policy in the collector where the rest of the estate's data-handling policy lives, and lets local (on-host) telemetry stay identifiable while exported telemetry is pseudonymised.

Default: identifiable. Regulated deployments flip it in collector config; `docs/PRIVACY.md` ships the processor block.

### 10.3 Retention
Out of scope for the extension — a backend concern. `docs/PRIVACY.md` states retention must be set explicitly, since access logs constituting audit evidence typically carry a defined minimum *and* maximum.

---

## 11. Compliance mapping

The value proposition is that access evidence becomes a byproduct of normal operation rather than a manual extraction from the Guacamole database.

| Requirement | Evidence produced |
|---|---|
| **NIS2 Art. 21** — access control, logging of privileged access | `guacamole.auth.*` log records; session records with user, target, protocol, duration |
| **DORA Art. 9/10** — ICT protection, anomaly detection | session concurrency and duration metrics; auth failure rate as a detection signal |
| **ISO 27001 A.8.15 / A.8.16** — logging, monitoring | full attempt-level auth log, session lifecycle |
| **Least-privilege review** | per-user, per-connection session records support periodic access recertification |

Non-repudiation caveat, to be stated plainly in the README: this telemetry is *operational*, not tamper-evident. It is not a substitute for a WORM audit store, and the collector pipeline is not an evidential chain of custody. Positioning it otherwise would be an audit finding.

---

## 12. Collector configuration bundle

Closes Gaps 1 and 2 with stock components only.

### 12.1 Receivers
```yaml
receivers:
  otlp:                          # from Java agent + extension
    protocols: { grpc: { endpoint: 127.0.0.1:4317 } }

  hostmetrics:                   # Gap 1 — guacd resource + throughput
    collection_interval: 30s
    scrapers:
      process:
        include: { names: [guacd], match_type: strict }
        metrics:
          process.network.io: { enabled: true }
      cpu: {}
      memory: {}

  journald:                      # Gap 2 — guacd + tomcat logs
    units: [guacd, tomcat]
    priority: info
```

### 12.2 Connection-ID extraction — Gap 2 correlation
```yaml
processors:
  transform/guacd:
    log_statements:
      - context: log
        statements:
          # PLACEHOLDER — pattern set after Gate 0.4
          - set(attributes["guacamole.connection.id"],
                ExtractPatterns(body, "connection \"(?P<cid>[^\"]+)\"")["cid"])
            where attributes["_SYSTEMD_UNIT"] == "guacd.service"
```
Once populated, guacd log lines and listener session telemetry share `guacamole.connection.id` and join in the backend. This is the whole of Gap 2.

### 12.3 Cardinality and privacy processors
```yaml
processors:
  attributes/pseudonymise:       # optional, see §10.2
    actions:
      - key: enduser.id
        action: hash
  batch:
    timeout: 10s
```

### 12.4 Optional legacy scrape
If `guacamole_exporter` is already deployed, a `prometheus` receiver ingests it. Redundant with the listener for active-connection counts and lower fidelity (polled, admin credentials, no protocol dimension). Recommended path is decommissioning it once the listener is validated.

---

## 13. Failure modes and guardrails

| # | Failure | Mitigation |
|---|---|---|
| 13.1 | Listener exception vetoes login/connection | `catch (Throwable)` in `handleEvent`; unit-tested with a deliberately throwing exporter |
| 13.2 | Registry grows unbounded → Tomcat OOM | hard max entries + TTL sweep + eviction counter |
| 13.3 | Metric cardinality explosion | code-level dimension allowlist; unit test asserts prohibited keys are unattachable |
| 13.4 | Directory lookup on event thread adds login latency | **VERIFIED moot** — Gate 0.3 needs no lookup: protocol + connection id come straight off `ConfiguredGuacamoleSocket` on the event thread. Keep only a null/`instanceof` guard → `protocol=unknown`, `connection.id` absent. No TTL cache. |
| 13.5 | Collector down → exporter backpressure | agent/SDK exporters are async with bounded queues; drop, never block. **Verify** queue is bounded and `BatchSpanProcessor` is in use, not `SimpleSpanProcessor` |
| 13.6 | OTLP endpoint misconfigured at startup | fail open — log once at WARN, continue with no-op; never prevent extension load |
| 13.7 | Credentials leaked into attributes | explicit allowlist; unit test asserts no attribute value equals the test password |
| 13.8 | Classloader mismatch → `NoClassDefFoundError` | Gate 0.1; covered by 13.1's `Throwable` catch as a runtime backstop |
| 13.9 | Duplicate SDK init (fallback path from Gate 0.1) | if bundling, shade and explicitly do not call `GlobalOpenTelemetry.set()` |

**Overhead budget:** < 1 ms added latency per event at p99, zero blocking I/O on the event thread. Measured under §15.3.

---

## 14. Build, packaging, installation

- **Build:** Maven, `maven.compiler.release=8` (**VERIFIED**: Guacamole 1.6.0 baseline
  is Java 8 bytecode; the 1.6.0 image runs JVM 21, so release-8 bytecode loads).
  `guacamole-ext` + `guacamole-common` scope `provided`. `opentelemetry-api` +
  `opentelemetry-context` **shaded into the jar** (Gate 0.1 corrected pass), not
  `provided`. Jar carries only the shaded API — target ≈ a few-hundred KB (probe
  reference: 212 KB).
- **Artefacts:** `.jar` on GitHub Releases; RPM via `nfpm` for RHEL9 placing the jar in `GUACAMOLE_HOME/extensions/` with correct ownership.
- **Install:** drop jar, restart servlet container. No database migration, no webapp rebuild, no guacd change.
- **Uninstall:** delete jar, restart. Complete removal — no residue in Guacamole's schema or config.
- **Configuration:** all OTel exporter config via standard `OTEL_*` environment variables on the JVM (pass case). Extension-specific keys in `guacamole.properties`, all prefixed `otel.`, all optional with safe defaults.

---

## 15. Testing and validation

**15.1 Unit** — JUnit with `InMemorySpanExporter`/`InMemoryMetricReader`. Assertions: connect/close produces one span with correct duration; unmatched close is a no-op; TTL sweep ends orphans with `end_reason=timeout`; prohibited metric dimensions cannot be attached; no attribute value contains the test password; a throwing exporter does not propagate out of `handleEvent`.

**15.2 Integration** — docker-compose: Guacamole + guacd + Postgres + otelcol + backend. Scripted RDP/VNC/SSH session against a throwaway target; assert end-to-end arrival and that `guacamole.connection.id` joins guacd logs to session telemetry.

**15.3 Load** — N concurrent sessions with rapid connect/close churn. Measure added login latency (p50/p99), registry high-water mark, JVM heap delta, and total metric series count. Gate: < 1 ms p99 added latency, series count independent of session count.

**15.4 Failure injection** — collector killed mid-session; OTLP endpoint pointed at a black hole; exporter throwing on every export. In all three, Guacamole logins and connections must succeed unaffected. This is the acceptance test that matters most.

---

## 16. Extension point — instruction-level telemetry (specified, not built)

Documented so the boundary is deliberate rather than discovered.

**Mechanism.** Decorate `UserContext` (`DelegatingUserContext`), intercept `Connection.connect()`, and wrap the returned `GuacamoleTunnel` in a `FilteredGuacamoleSocket` with a custom `GuacamoleFilter`. These classes ship in `guacamole-common`/`guacamole-ext` — the mechanism is stock. The filter body is not, and it executes on every instruction in both directions. **VERIFY** `DelegatingUserContext.decorate()` still exposes `Connection.connect()` in 1.6.x and that `FilteredGuacamoleSocket` is reachable from an extension classloader.

**Why deferred.**
1. It is novel code in the data path — a direct violation of the governing principle, and a latency and correctness risk on every frame.
2. The high-value opcodes for compliance (`clipboard`, `file`) are already captured by Guacamole's built-in session recording for forensic purposes.
3. A filter observing `key` instructions is keystroke logging regardless of intent — DPIA and works-council territory, not observability.

**Constraints if ever built.** Separate artefact, separate jar, disabled by default, explicit opt-in. Strict opcode **allowlist**: `size`, `clipboard`, `file`, `disconnect`. `key`, `mouse`, `blob`, `img`, `sync` never inspected — they are both the high-rate opcodes and the privacy landmines. Metadata only (that a clipboard event occurred, and its size); never content.

**Recommendation:** do not build. Ship v1 and see whether anyone asks.

---

## 17. Roadmap

| Phase | Content | Gate to exit |
|---|---|---|
| **0** | Gates 0.1–0.4 | all four answered, Gate 0.1 branch chosen |
| **1** | Listener: session spans, session metrics, auth log records | §15.1 + §15.4 green |
| **2** | Collector bundle: journald + hostmetrics + connection-ID extraction | §15.2 green — logs join telemetry |
| **3** | Dashboard, RPM, install runbook, `PRIVACY.md` | deployable by a third party from docs alone |
| **4** | Load validation, overhead budget confirmed | §15.3 green |
| **5** *(optional)* | `guacamolereceiver` for `opentelemetry-collector-contrib` — replaces the polling exporter, ecosystem-native contribution | — |
| **6** *(long horizon)* | OTLP or metrics endpoint in `guacd` upstream (C) — the only durable fix for guacd internals | — |

---

## Appendix A — open decisions

| # | Decision | Options | Recommendation |
|---|---|---|---|
| A1 | Repository name | `guacamole-otel`, `guacamole-otel-extension`, `otel-guacamole` | `guacamole-otel` — covers extension + collector bundle |
| A2 | Licence | Apache-2.0, AGPLv3 | **Apache-2.0** — matches Guacamole and OTel; AGPL blocks the §17 Phase 5/6 upstream path |
| A3 | SDK strategy | agent-bridge (`provided`) vs bundled | agent-bridge if Gate 0.1 passes |
| A4 | Metric namespace | custom `guacamole.*` vs semconv mapping | custom, as specified — sessions are not RPCs |
| A5 | v1 scope | listener only vs listener + collector bundle | **both** — Gap 2 correlation is what makes the listener worth deploying |
| A6 | `enduser.id` default | identifiable vs hashed | identifiable by default, collector-side hashing documented |
| A7 | Contrib receiver (Phase 5) | in scope vs out | out for v1; revisit after adoption |

---

## Appendix B — claims requiring verification before implementation

Items below are from API familiarity, not fetched source. Each is a Phase 0 or in-phase check.

1. `GlobalOpenTelemetry` resolution across Guacamole's extension classloader (Gate 0.1)
2. Complete 1.6.x event type inventory and accessor signatures (Gate 0.2)
3. Whether `TunnelCloseEvent` carries a close reason
4. Traversal from `TunnelConnectEvent` to connection ID and protocol (Gate 0.3)
5. guacd log line format and connection-ID presence at info level (Gate 0.4)
6. Whether `Context.current()` in a listener carries the agent's server span (§7.3)
7. `DelegatingUserContext.decorate()` / `FilteredGuacamoleSocket` reachability (§16)
8. `guac-manifest.json` schema exactness for 1.6.x — `listeners` key and version pinning syntax
