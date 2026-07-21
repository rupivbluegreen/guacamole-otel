# VERIFIED.md — resolved API claims and gate results

Append-only. Every SPEC.md Appendix B claim resolved here with evidence
(fetched URL or command output) before dependent code is written.
Gate results recorded per the format in BUILD-PHASES.md.

## 2026-07-21 — Task 0.1 Source-of-truth capture

**Sources (authoritative, fetched — not training memory):**
- `guacamole-ext` 1.6.0 sources jar — `https://repo1.maven.org/maven2/org/apache/guacamole/guacamole-ext/1.6.0/guacamole-ext-1.6.0-sources.jar`
- `guacamole-common` 1.6.0 sources jar — `https://repo1.maven.org/maven2/org/apache/guacamole/guacamole-common/1.6.0/guacamole-common-1.6.0-sources.jar`
- `ExtensionManifest.java` @ tag 1.6.0 — `https://raw.githubusercontent.com/apache/guacamole-client/1.6.0/guacamole/src/main/java/org/apache/guacamole/extension/ExtensionManifest.java`
- root `pom.xml` @ tag 1.6.0 — `https://raw.githubusercontent.com/apache/guacamole-client/1.6.0/pom.xml`

### Appendix B #2 — event type inventory (Gate 0.2)

`Listener` interface: `org.apache.guacamole.net.event.listener.Listener`, single
method `void handleEvent(Object event) throws GuacamoleException`. Matches SPEC
§6.2 exactly. Listeners notified in manifest order; throwing vetoes the action.

Full 1.6.0 event class set (`org.apache.guacamole.net.event`), richer than the
4 SPEC assumed:
- `AuthenticationSuccessEvent`, `AuthenticationFailureEvent`
- `TunnelConnectEvent`, `TunnelCloseEvent`
- `AuthenticationRequestReceivedEvent`, `AuthenticationProviderEvent`
- `CredentialEvent`, `FailureEvent`, `UserEvent`, `TunnelEvent` (marker/base ifaces)
- `DirectoryEvent`, `DirectorySuccessEvent`, `DirectoryFailureEvent`, `IdentifiableObjectEvent`
- `ApplicationStartedEvent`, `ApplicationShutdownEvent`, `UserSessionInvalidatedEvent`

v1 consumes only the connect/close + auth pair. The rest are upside, out of scope for Phase 1.

Accessor signatures (from source):
- `TunnelConnectEvent implements UserEvent, CredentialEvent, TunnelEvent`
  → `getAuthenticatedUser()`, `getCredentials()`, `getTunnel()`. **No `getUserContext()`** despite the javadoc prose.
- `AuthenticationSuccessEvent` → `getAuthenticatedUser()`, `getCredentials()` (= `authenticatedUser.getCredentials()`), `getAuthenticationProvider()`, **`isExistingSession()`**.
- `AuthenticationFailureEvent implements AuthenticationProviderEvent, CredentialEvent, FailureEvent`
  → `getCredentials()`, `getAuthenticationProvider()` (nullable), **`getFailure()` → `Throwable`** (nullable).

**Schema-shaping noise findings (feed Phase 1):**
- `AuthenticationSuccessEvent.isExistingSession()` is `true` for periodic token
  re-auth (every few min per active session), `false` for a fresh login. A raw
  `auth.success` per event is noisy — Phase 1 must tag `guacamole.auth.existing_session`
  and/or treat only `false` as a login.
- `Credentials.isEmpty()` is `true` for the initial anonymous credential-less hit
  that renders the login screen; it fires an `AuthenticationFailureEvent`. Phase 1
  must skip empty-credential failures or they dominate `auth.failure`.

### Appendix B #3 — does TunnelCloseEvent carry a close reason? — **NO**

`TunnelCloseEvent` fields are exactly `authenticatedUser`, `credentials`,
`tunnel` — accessors `getAuthenticatedUser/getCredentials/getTunnel` only. No
status, no reason, no Throwable. **Consequence:** SPEC §7.1 span status can only
be `OK` on clean close; there is no close-carried failure to map to `ERROR`.
`guacamole.session.end_reason` ∈ {`closed`, `timeout`} only — drop `error` as a
close outcome. Update SPEC §7.1 accordingly in Phase 1.

### Appendix B #4 — connect → connection id + protocol traversal (Gate 0.3, static portion)

`GuacamoleTunnel` (guacamole-common) exposes only `getUUID()` (per-tunnel UUID),
`getSocket()`, reader/writer, `close()`, `isOpen()`. **No connection id, no
protocol, no config on the tunnel itself.** Traversal is through the socket:

- **protocol** — `GuacamoleSocket.getProtocol()` is a `default` interface method;
  `ConfiguredGuacamoleSocket` overrides it to return `config.getProtocol()`, and
  `DelegatingGuacamoleSocket.getProtocol()` delegates down the wrap chain. So
  `tunnel.getSocket().getProtocol()` yields the protocol (`rdp`/`vnc`/`ssh`/…)
  **through any delegation layer, zero cost, no directory lookup.** → SPEC §13.4
  TTL cache is NOT needed for protocol (pending runtime confirmation the value is
  populated at connect time). On null, emit `protocol=unknown` (L5).
- **connection id (guacd correlation key)** — `ConfiguredGuacamoleSocket.getConnectionID()`
  returns the id from guacd's `ready` instruction — the same id guacd logs
  reference (Gate 0.4). BUT this method is on the **concrete** class only, not on
  the `GuacamoleSocket` interface, and `getDelegateSocket()` is `protected`. If
  the runtime socket is wrapped (Filtered/Monitoring/Delegating), an
  `instanceof ConfiguredGuacamoleSocket` check fails and the id is unreachable
  without reflection. **This is the runtime unknown Gate 0.2/0.3 probe must
  resolve: what concrete socket type is `getSocket()` at `TunnelConnectEvent` time?**
- Distinguish two "connection ids": `ConfiguredGuacamoleSocket.getConnectionID()`
  (guacd `ready` id, the correlation key) vs `GuacamoleConfiguration.getConnectionID()`
  (id of a connection being *joined*, usually null). SPEC's `guacamole.connection.id`
  = the former.
- **connection *name*** (`prod-jump-01`, SPEC §7.1) lives on the connection object
  in the directory; reaching it needs a `UserContext` → `getConnectionDirectory()`
  lookup, and `TunnelConnectEvent` gives no `UserContext`. Likely dropped or
  deferred in Phase 1 unless the probe finds another path. Flag for Gate 0.3.

### Credentials accessors + G3

`Credentials` (guacamole-ext): SAFE fields for telemetry — `getUsername()`
(→ `enduser.id`; or prefer `AuthenticatedUser.getIdentifier()`, `AuthenticatedUser
extends Identifiable`, `ANONYMOUS_IDENTIFIER=""`), `getRemoteAddress()`
(→ `client.address`), `getRemoteHostname()`. **`getPassword()` exists and MUST
never be called (G3).** Also avoid the generic `getHeader/getParameter/getRequestDetails/
getRequest(deprecated)` paths — allowlist only.

### Appendix B #8 — guac-manifest.json schema — RESOLVED

`ExtensionManifest` JSON keys (field name = JSON key unless `@JsonProperty`):
`guacamoleVersion` (String), `name` (String), `namespace` (String),
`js`/`css`/`html`/`translations`/`resources`, `authProviders` (Collection<String>),
**`listeners` (Collection<String>** — FQCNs of `Listener` impls), `smallIcon`,
`largeIcon`. Confirms SPEC §6.1 manifest verbatim. Open: the `guacamoleVersion`
value is a plain String; the `"1.6.*"` wildcard match semantics are enforced by
the loader, not this class — confirm the running app accepts `1.6.*` during the probe.

### Appendix B #7 — §16 filter mechanism reachability (docs only, stays UNBUILT)

Confirmed reachable from an extension classpath: subclass `DelegatingUserContext`
(exposes `getConnectionDirectory() → Directory<Connection>`), wrap the tunnel's
socket in `FilteredGuacamoleSocket(GuacamoleSocket, GuacamoleFilter readFilter,
GuacamoleFilter writeFilter)`, `GuacamoleFilter.filter(GuacamoleInstruction)`.
Mechanism is stock; per governing principle it is **not built**. Recorded for
`docs/EXTENSION-POINTS.md` only.

### Java baseline — CONFLICT with CLAUDE.md/SPEC (needs human decision)

guacamole-client 1.6.0 root `pom.xml` compiles at `<source>1.8</source>
<target>1.8</target>` → **Java 8 bytecode**. CLAUDE.md "Environment facts" and
SPEC say "Java 17 (verify, G8)". **Verified: baseline is Java 8, not 17.** The
extension is loaded by whatever JVM runs Tomcat; targeting Java 8 bytecode
(`maven.compiler.release=8`, build with any JDK ≥8) maximises load-compat. OTel
Java SDK supports Java 8. Recommendation: build extension with `release=8` (or 11
if the deployment JVM is pinned newer). **Do not silently change CLAUDE.md (G10);
raise at the Phase 0 human gate.**
`guacamole-ext` provides (transitively, `provided` scope): guava, jackson-databind,
ipaddress, servlet-api — available at runtime, need not be bundled.

### Still open — require the running probe (Gates 0.1, 0.3-runtime, 0.4)

- **Gate 0.1** (blocking): `GlobalOpenTelemetry.get().getClass().getName()` from the
  extension classloader under the real OTel Java agent → SDK class (agent-bridge)
  vs no-op (bundled-SDK). Static analysis cannot answer this.
- **Gate 0.3-runtime:** concrete type of `tunnel.getSocket()` at connect time —
  determines whether `getConnectionID()` is reachable and whether protocol is
  populated then.
- **Gate 0.4:** guacd log line format + whether the `ready`/connection id appears
  at info level; yields the collector `transform` regex.

_Gate P0 exit NOT marked passed — pending probe + human review (G10)._

---

## 2026-07-21 — Gate 0.2 event dispatch (probe-confirmed)

**Method:** probe `Listener` logging every `event.getClass().getName()`, guacamole
1.6.0 image (Tomcat 9.0.106, **JVM Eclipse Adoptium 21.0.7** — note runtime is
Java 21, extension bytecode release-8 loads fine), OTel agent 2.29.0 attached.

Observed event object classes (evidence — `docker compose logs guacamole`):
- `org.apache.guacamole.GuacamoleServletContextListener$3` (app-started, **anonymous
  inner class** — dispatch must use `instanceof` on public event types, never class
  equality; SPEC §6.2 `instanceof` chain is correct)
- `org.apache.guacamole.rest.auth.AuthenticationService$$Lambda/0x...` (internal, pre-auth)
- `org.apache.guacamole.net.event.AuthenticationSuccessEvent` (public — matched)
- `org.apache.guacamole.net.event.TunnelConnectEvent` (public — matched)

Confirms the §6.2 dispatch model: filter by `instanceof` against the four public
event types; internal/lambda/anonymous event objects fall through harmlessly.

## 2026-07-21 — Gate 0.1 classloader visibility — **PASS (agent-bridge), with corrected dependency scope**

Result: **PASS** — `GlobalOpenTelemetry.get()` from the Guacamole extension
classloader returns the agent's live SDK bridge, **provided `opentelemetry-api`
is bundled (shaded) into the extension jar.**

Evidence (probe, after bundling api):
```
GATE-0.1 GlobalOpenTelemetry.get()
  class=io.opentelemetry.javaagent.instrumentation.opentelemetryapi.v1_27.ApplicationOpenTelemetry127
  tracerProvider=...ApplicationTracerProvider14
  loadedBy=org.apache.guacamole.extension.ExtensionClassLoader@49136fb
```
`ApplicationOpenTelemetry127` is the agent's bridge (NOT `DefaultOpenTelemetry`/no-op),
loaded in Guacamole's isolated `ExtensionClassLoader`.

**Correction to SPEC §4/§14 — the "Pass = provided scope" premise is FALSE.**
First attempt with `opentelemetry-api` scope `provided` (SPEC's design) →
`NoClassDefFoundError: io/opentelemetry/api/GlobalOpenTelemetry`. The Java agent
**shades** its own API to `io.opentelemetry.javaagent.shaded.*` and does NOT expose
the unshaded `io.opentelemetry.api.*` to the app/extension classloader. The working
pattern:
- **Bundle `opentelemetry-api` (+ `opentelemetry-context`) shaded into the extension
  jar** (compile scope). The agent instruments these app-side API classes and bridges
  `GlobalOpenTelemetry` to its configured SDK.
- Config surface stays single: all `OTEL_*` env on the JVM (the agent's SDK) governs
  the extension's spans/metrics. The agent-bridge benefit is preserved.
- We do **NOT** bundle the SDK or exporters (agent supplies them).
- **Jar-size target correction:** SPEC §14 / BUILD-PHASES P1 "< 100 KB" assumed
  `provided` api. Real target with shaded api+context ≈ a few hundred KB (probe jar
  = 212 KB). Update the exit-gate size assertion accordingly.

Branch chosen: **agent-bridge (corrected)** — `opentelemetry-api`+`opentelemetry-context`
shaded in; SDK/exporter/config from the agent. Bundled-SDK fallback NOT needed.

## 2026-07-21 — Gate 0.3 connection metadata traversal — **PASS, no cache needed**

Result: from `TunnelConnectEvent`, both protocol and the guacd connection id are
reachable **directly on the event thread, zero blocking I/O, no directory lookup,
no TTL cache.**

Evidence (probe):
```
GATE-0.3 tunnelUuid=8063df49-c55f-347b-802d-0c1168c0c119
  socketClass=org.apache.guacamole.protocol.ConfiguredGuacamoleSocket
  protocol=ssh  isConfiguredSocket=true
  guacdConnectionId=$2a5e6978-8fc8-4373-890f-b808bc4a29b4
```
- Runtime `tunnel.getSocket()` is a **bare `ConfiguredGuacamoleSocket`** (not wrapped
  by Filtered/Monitoring/Delegating) → `instanceof ConfiguredGuacamoleSocket` holds,
  `getConnectionID()` reachable.
- `protocol` via `getProtocol()` = `ssh`, zero cost.
- **Consequence for design:** SPEC §13.4 TTL cache and the L5 "protocol=unknown on
  miss" path are NOT required for protocol/connection-id (keep `protocol=unknown` only
  as a null-guard). Traversal for Phase 1:
  `s = tunnel.getSocket(); if (s instanceof ConfiguredGuacamoleSocket cs) { protocol=cs.getProtocol(); connId=cs.getConnectionID(); }`
  with a null/`instanceof`-false fallback to `protocol=unknown`, `connection.id` absent.
- Caveat carried forward: robustness should still `instanceof`-guard (a future
  Guacamole build could wrap the socket); on miss, degrade, never block.
- `guacamole.connection.name` (human name) still NOT available from the event; drop
  it from the Phase 1 span schema or resolve out-of-band. `guacamole.tunnel.uuid` =
  `tunnel.getUUID()` (span-only).

## 2026-07-21 — Gate 0.4 guacd log format + correlation — **PASS at INFO**

Result: guacd emits the connection id **at default INFO level** (no debug needed),
and it **matches the listener's `getConnectionID()` exactly** → end-to-end
correlation (Gap 2) confirmed.

Evidence (`docker compose logs guacd`, one SSH session):
```
guacd[1]:  INFO:	Creating new client for protocol "ssh"
guacd[1]:  INFO:	Connection ID is "$2a5e6978-8fc8-4373-890f-b808bc4a29b4"
guacd[16]: INFO:	User "@cd78fae2-..." joined connection "$2a5e6978-8fc8-4373-890f-b808bc4a29b4" (1 users now present)
guacd[16]: INFO:	SSH connection successful.
```
- Same id `$2a5e6978-8fc8-4373-890f-b808bc4a29b4` as the socket in Gate 0.3. The
  `$`-prefix is part of the id.
- Docker stdout line format: `guacd[<pid>]: <LEVEL>:\t<message>` (tab after level).
- Collector `transform` regex (message body): `Connection ID is "(?P<cid>[^"]+)"`
  (or `joined connection "(?P<cid>[^"]+)"`). SPEC §12.2 placeholder
  `connection "(?P<cid>...)"` is close but the real prefix is `Connection ID is "..."`.
- **Deployment note:** SPEC §12.1 uses a `journald` receiver keyed on
  `_SYSTEMD_UNIT == guacd.service`. This probe used Docker stdout (no journald);
  the *message-body* regex is identical, only the log-source wiring differs. Confirm
  the systemd MESSAGE field carries the same `INFO:\t...` body in the Phase 2 itest.

---

## 2026-07-21 — Gate P0 exit (proposed)

| Gate | Result | Key consequence |
|---|---|---|
| 0.1 classloader | **PASS** (agent-bridge) | bundle+shade `opentelemetry-api`(+context); NOT `provided`; SDK/config from agent; jar ≈ few-hundred KB |
| 0.2 event inventory | **RESOLVED** | 4 public events consumed; `instanceof` dispatch; `TunnelCloseEvent` has no close reason; auth re-auth/empty noise to filter |
| 0.3 metadata | **PASS** | protocol + guacd connection-id direct from bare `ConfiguredGuacamoleSocket`, zero I/O, no TTL cache |
| 0.4 guacd logs | **PASS** | connection id at INFO, `Connection ID is "$<uuid>"`, matches listener id — Gap 2 joins |

**SPEC corrections required before Phase 1 (need human approval, G10):**
1. §4 Gate 0.1 / §14: `opentelemetry-api` **bundled+shaded**, not `provided`; drop "< 100 KB" jar target (≈ few-hundred KB).
2. §7.1: `end_reason` ∈ {`closed`,`timeout`} only — `TunnelCloseEvent` carries no failure; remove `error` close status.
3. §6.1: manifest `guacamoleVersion` must be `"1.6.0"` (exact) or `"*"` — `"1.6.*"` is rejected by the loader.
4. §13.4/L5: no TTL cache needed for protocol/connection-id; keep null-guard → `protocol=unknown` only.
5. §7.1: drop `guacamole.connection.name` (not reachable from the event) or mark out-of-band.
6. §9: filter `AuthenticationSuccessEvent.isExistingSession()==true` (re-auth) and `Credentials.isEmpty()` failures (anonymous login-screen hits) to avoid log-record noise.
7. CLAUDE.md/SPEC env: build target **Java 8** (runtime image is JVM 21; release-8 bytecode loads).

## 2026-07-21 — Gate P0
Result: PASS
Evidence: Gates 0.1–0.4 above (probe logs, guacd logs, fetched 1.6.0 source).
  Human review of VERIFIED.md + sign-off recorded in session 2026-07-21.
Consequence: agent-bridge branch locked (`opentelemetry-api`+`opentelemetry-context`
  shaded into the extension jar; SDK/exporter/config from the OTel Java agent).
  Manifest `guacamoleVersion` = `"*"`. Build target Java 8. The 7 SPEC/CLAUDE
  corrections applied in the same session. Phase 1 unblocked.

## 2026-07-21 — Gate P1
Result: PASS
Evidence: `mvn verify` green, 19 unit tests (SPEC §15.1 set: connect/close single
  span + duration + attrs; unmatched close no-op + counted; TTL sweep timeout;
  G4 prohibited dims unattachable; G3 no attribute equals the test password; G1
  throwing exporter does not propagate; capacity eviction bounded + counted).
  Live itest: extension "OpenTelemetry Instrumentation" (otel) loads; a driven SSH
  session emits auth-success + session-connected/closed log records and a bridged
  `guacamole.session` SERVER span carrying `guacamole.connection.id=$63d4c7c9-...`
  (the guacd correlation key), zero suppressed errors.
Consequence: human approved the G1 handleEvent envelope verbatim and the Attributes
  allowlist. Unmatched-close counted on `guacamole.otel.errors` stage=`unmatched_close`
  (fits §8's existing 7 instruments; no 8th added). Jar 232 KB (agent-bridge shaded).
  Phase 2 (collector bundle + correlation) unblocked.

## 2026-07-21 — Gate P2
Result: PASS
Evidence: `collector/otelcol-guacamole.yaml` passes `otelcol-contrib validate`.
  itest (`itest/run-correlation-test.sh`): (a) `guacamole.session` span arrived on
  close; (b) `guacamole.session.connected` log arrived at connect; (c) a guacd log
  line `Connection ID is "$48e14a8e-..."` and the session telemetry shared
  `guacamole.connection.id` via the transform/guacd processor (8 records, same id).
  Kill test: collector stopped mid-flight, login succeeded (115 ms) and the SSH
  session established (guacd "SSH connection successful"), guacamole healthy, no OOM
  (bounded-queue drop, §13.5).
Consequence: human sign-off recorded. Two config deviations noted in the Phase 2
  commit — SPEC §12.1 `process.network.io` dropped (not enableable in current
  otelcol-contrib process scraper); the itest file exporter is routed off metrics
  (contrib 0.156 nil-panics exporting metrics to file). Phase 3 unblocked.

## 2026-07-21 — Gate P3
Result: PASS
Evidence: nfpm RPM built and verified in rockylinux:9 — `rpm -qlp` lists only
  `/etc/guacamole/extensions/guacamole-otel-extension.jar`, `rpm -qp --scripts`
  shows no scriptlets, `rpm -i` then `rpm -e` leaves the extensions dir empty.
  Dashboard JSON validated. INSTALL/PRIVACY/EXTENSION-POINTS/README written; CI pins
  tim-actions/dco to a commit SHA and runs the license check via mvn verify;
  release.yml is workflow_dispatch-only, build-only (no publish, G10).
Consequence: human approved the README compliance claims and PRIVACY.md. Clean-room
  RPM mechanics verified on RHEL9; the jar loads + emits in the live guacamole stack.
  Follow-up requested: add OpenShift + Docker packaging alongside the RPM. Phase 4
  unblocked.

## 2026-07-21 — Gate P4 (load & failure validation)
Result: PASS (measurement)
Evidence:
- **Overhead budget (§15.3): MET with ~30x headroom.** Per-event latency measured in
  isolation (in-memory SDK, no network/password-hashing), 50 000 connect/close pairs
  after 20 000 warm-up: p50=0.0051 ms, p99=0.0339 ms, p99.9=0.0659 ms — well under
  the < 1 ms p99 budget. (`OverheadBenchmarkTest`.)
- **Series-count independent of session count.** Driving 6 sessions yielded a single
  `guacamole.session.started` series `{guacamole.protocol=ssh, guacamole.datasource=postgresql}`
  with value 6 — one series, not one-per-session. Enforced by the type-level
  cardinality guard (G4, `AttributesTest`).
- **Failure injection (§15.4) — all clean.** (a) collector killed mid-session (Gate P2);
  (b) collector **down from start** — guacamole boots, "OpenTelemetry Instrumentation"
  loads, login succeeds, guacd establishes the SSH session, no OOM, no suppressed
  errors; (c) exporter throwing on every call — `handleEvent` returns normally
  (`OtelListenerTest`, G1). A dead/black-holed OTLP endpoint is the connection-refused
  equivalent of (b).
- **Registry bounded (G6):** capacity eviction + TTL sweep unit-tested; hard max via
  `otel.registry.max-entries`.
Consequence: the acceptance property holds — a telemetry backend failure never affects
  Guacamole login or session availability. The v0.1.0 tag + GitHub release remain
  **gated on human approval (G10)** and were not performed.

## 2026-07-21 — otel-api ↔ agent bridge version coupling (Dependabot #5)
Finding: the shaded `opentelemetry-api` version must stay within the range the
  deployed OTel Java **agent** bridges. Bumping the `opentelemetry-bom` 1.43.0 → 1.64.0
  against agent 2.29.0 **silently disables the extension**: `GlobalOpenTelemetry.get()`
  resolves to a no-op (not the agent bridge), so spans/metrics/logs are dropped — with
  **no error** and **green unit tests/CI** (tests use a real SDK, not the agent bridge).
Evidence: extension rebuilt at otel 1.64, deployed to the itest stack. The collector
  received the agent's own `jvm.*` metrics (OTLP transport fine) but **zero
  `guacamole.*`** telemetry; no `NoClassDefFoundError`, no suppressed-error logs. At
  1.43 (Gate P1/P2 runs) the same path emitted the full session span + auth/session logs.
Consequence: `opentelemetry-bom` minor/major bumps are ignored in `dependabot.yml`
  (patch-only); bump `opentelemetry-api` only in lockstep with an agent upgrade. The
  live agent-bridge boot is a required check for any OTel version change — unit tests
  cannot catch this.
