# BUILD-PHASES.md — Claude Code execution plan

Each phase is designed to be run as one or more Claude Code sessions. Every
phase states: objective, the single failure class it isolates, tasks, exit
gate (with the evidence required to pass), human gates, and rollback. Phases
are strictly sequential (CLAUDE.md, Build discipline). Record every gate
result in `docs/VERIFIED.md` in the format:

```
## <date> — Gate <id>
Result: PASS | FAIL
Evidence: <command output / fetched URL / test name>
Consequence: <branch taken, if any>
```

---

## Phase 0 — Verification gates

**Objective:** answer the eight unverified claims in SPEC.md Appendix B before
any implementation. **Failure class isolated:** designing against an API that
does not exist.

### Task 0.1 — Source-of-truth capture
Fetch and cache locally (do not rely on training memory):
- `guacamole-ext` 1.6.0 javadoc pages for `Listener`, all `*Event` classes,
  `GuacamoleTunnel`, `UserContext`, `DelegatingUserContext`
- the 1.6.0 manual chapter on event listeners and `guac-manifest.json`
- `FilteredGuacamoleSocket` / `GuacamoleFilter` javadoc (for SPEC §16 docs only)

Write findings into `docs/VERIFIED.md`: exact event class inventory, accessor
signatures, whether `TunnelCloseEvent` carries a close reason, manifest schema.
This resolves Appendix B items 2, 3, 7, 8 and SPEC Gates 0.2.

### Task 0.2 — Classloader probe (Gate 0.1, blocking)
Build a throwaway extension `probe/` containing one listener that logs
`GlobalOpenTelemetry.get().getClass().getName()` on auth events. Stand up the
`itest/` docker-compose with the OTel Java agent attached to the guacamole
container. Log in; capture the class name.
- SDK class → **PASS**: record "agent-bridge branch; `opentelemetry-api`
  scope `provided`".
- `DefaultOpenTelemetry`/no-op or `NoClassDefFoundError` → **FAIL**: record
  "bundled-SDK branch; shade SDK+OTLP exporter; config via
  `guacamole.properties`; do NOT call `GlobalOpenTelemetry.set()`".

### Task 0.3 — Connection metadata traversal (Gate 0.3)
In the probe, from `TunnelConnectEvent`, attempt to reach connection ID and
protocol. Record the exact traversal, whether it needs a directory lookup on
the event thread, and therefore whether the TTL cache (SPEC §13.4) is required.

### Task 0.4 — guacd log capture (Gate 0.4)
From the compose stack, capture guacd journald/stdout output for one full
session at default log level and at debug. Record: line format, whether the
connection ID appears at info, and the exact regex for the collector
`transform` processor.

**Exit gate P0:** all four tasks recorded in `docs/VERIFIED.md` with evidence;
Gate 0.1 branch chosen. **Human gate:** human reviews VERIFIED.md and approves
the branch before Phase 1.
**Rollback:** delete `probe/`; nothing else exists yet.

---

## Phase 1 — Listener extension

**Objective:** `extension/` module producing session spans, session metrics,
and auth log records. **Failure class isolated:** listener correctness and
isolation — a telemetry defect must never affect Guacamole availability.

### Tasks, in order
1. `extension/pom.xml`: `guacamole-ext` provided; OTel per the Phase 0 branch;
   `opentelemetry-bom` pinned; surefire; ASF headers plugin.
2. `guac-manifest.json` exactly per the schema captured in Task 0.1.
3. `Attributes.java` first — the allowlist and the metric-dimension builder
   that cannot attach prohibited keys (G3, G4). Then its unit tests. Writing
   this before the listener makes the invariant load-bearing from the start.
4. `SessionRegistry.java`: ConcurrentHashMap, hard max (default 10 000), TTL
   sweep (default 24 h) on a single daemon scheduler thread, eviction counters.
5. `Instruments.java`: the seven instruments from SPEC §8, explicit histogram
   boundaries from §8.2.
6. `SessionSpans.java` + `AuthEvents.java`: span lifecycle per SPEC §7,
   log records per SPEC §9. Span start/end timestamps from event time.
7. `OtelListener.java` last: dispatch + the G1 `Throwable` envelope +
   self-telemetry counters.
8. `Config.java`: `guacamole.properties` keys, all `otel.`-prefixed, all
   optional, safe defaults (`otel.metrics.include-user=false`,
   `otel.attributes.hash-user=false`, registry bounds, TTL).

### Required tests (SPEC §15.1) — all must exist before exit
- connect→close ⇒ exactly one span, duration correct, attributes complete
- close without connect ⇒ no-op, counter incremented
- TTL sweep ends orphan with `end_reason=timeout`
- prohibited metric dimensions unattachable (compile-level where possible,
  runtime assertion otherwise)
- no attribute value equals the test password (G3)
- exporter that throws on every call ⇒ `handleEvent` returns normally (G1)
- registry at capacity ⇒ eviction, no growth, counter incremented

**Exit gate P1:** `mvn -q verify` green; test list above present and green;
jar size recorded (< 100 KB agent-bridge branch). **Human gate:** approve the
`handleEvent` envelope verbatim (G1) and the Attributes allowlist.
**Rollback:** the module is additive; revert the Phase 1 commits.

---## Phase 2 — Collector bundle + end-to-end correlation

**Objective:** `collector/otelcol-guacamole.yaml` + systemd unit; guacd logs
joined to session telemetry. **Failure class isolated:** pipeline and
correlation — data arrives, joins, and nothing sensitive leaks in transit.

### Tasks
1. Receivers per SPEC §12.1: otlp (localhost only), hostmetrics with guacd
   process scoping, journald for guacd + tomcat at info.
2. `transform/guacd` processor with the real regex from Task 0.4, promoting
   `guacamole.connection.id`.
3. `attributes/pseudonymise` block present but commented, referenced from
   PRIVACY.md (A6: identifiable by default).
4. `itest/` scripted session (SSH against the throwaway target — cheapest
   protocol): assert in the backend that (a) the session span arrived on
   close, (b) `guacamole.session.connected` log record arrived at connect
   time, (c) a guacd log line and the session share `guacamole.connection.id`.
5. Negative test: kill otelcol mid-session; assert login + session unaffected
   and JVM heap stable (bounded queue drop, SPEC §13.5).

**Exit gate P2:** correlation assertion green; kill-test green; collector
config passes `otelcol validate`. **Human gate:** none beyond commit approval.
**Rollback:** config-only; revert.

---

## Phase 3 — Packaging, docs, dashboard

**Objective:** deployable by a third party from the repo alone.
**Failure class isolated:** operability — install, configure, remove.

### Tasks
1. `nfpm` RPM: jar → `GUACAMOLE_HOME/extensions/`, correct ownership, no
   scriptlet surprises; clean `rpm -e` leaves nothing.
2. `docs/INSTALL.md`: jar drop + env vars (agent-bridge) or properties
   (bundled); uninstall; upgrade.
3. `docs/PRIVACY.md`: attribute inventory from `Attributes.java` (generated,
   not hand-maintained if cheap), pseudonymisation config, retention note,
   the "operational, not tamper-evident" caveat from SPEC §11 verbatim.
4. `dashboards/guacamole-overview.json`: active sessions (metric, never span
   count — SPEC §7.2), auth failure rate, duration heatmap, top protocols,
   self-telemetry (otel errors, registry size/evictions).
5. README: what it is, what it deliberately is not (SPEC §2 and §16 summary),
   quickstart, compliance table from SPEC §11.
6. CI (`.github/workflows/ci.yml`): mvn verify, license-header check, DCO
   check; release workflow drafted but **not** enabled (G10).

**Exit gate P3:** a clean-room install following only INSTALL.md succeeds in
the compose stack. **Human gate:** approve README compliance claims and
PRIVACY.md. **Rollback:** additive; revert.

---

## Phase 4 — Load and failure validation

**Objective:** confirm the overhead budget and the acceptance property.
**Failure class isolated:** behaviour under stress.

### Tasks
1. Load harness: N concurrent scripted SSH sessions with connect/close churn
   (N configurable; start 200). Measure: added login latency p50/p99 vs
   agent-only baseline, registry high-water mark, heap delta, metric series
   count.
2. Failure injection (SPEC §15.4): otelcol down from start; OTLP black-holed;
   exporter throwing. In all three: logins and sessions unaffected.
3. Record results in `docs/VERIFIED.md`. Budget: < 1 ms p99 added latency;
   series count independent of session count.

**Exit gate P4:** budget met, all three injections clean. **Human gate:**
approve v0.1.0 tag + GitHub release (G10).
**Rollback:** n/a — measurement only.

---

## Phase 5 (optional, separate approval) — collector-contrib receiver
`guacamolereceiver` (Go) for opentelemetry-collector-contrib, modeled on
`apachereceiver`. Do not start without an explicit new instruction; it is a
different codebase, language, and upstream process.

## Phase 6 (long horizon) — guacd upstream
OTLP/metrics endpoint in guacd via Apache JIRA + dev list. Out of Claude Code
scope until a design discussion happens upstream. Do not write C patches
speculatively (G7).
