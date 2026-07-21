# Roadmap

This roadmap sets contributor expectations: what is done, what is in progress,
and what is intentionally out of scope. It is derived from the phase plan in
[`docs/SPEC.md`](docs/SPEC.md) §17 and the boundary in §16. It is a statement of
direction, not a commitment or a schedule — priorities shift with adoption and
maintainer time. Substantive additions are decided per [`GOVERNANCE.md`](GOVERNANCE.md).

> guacamole-otel is an independent, third-party project and is **not** affiliated
> with or endorsed by the Apache Software Foundation or the OpenTelemetry project.

## Guiding principle

**No novel code in the Guacamole data path.** The project instruments Guacamole
through the supported `guacamole-ext` `Listener` API and stock OpenTelemetry
components only. Work that would intercept/filter the Guacamole protocol stream,
patch guacd, or fork guacamole-client is out of scope — the reasoning is in
`docs/SPEC.md` §16 and `docs/EXTENSION-POINTS.md`. Anything on this roadmap that
touches guacd internals is a proposal to fix them **upstream**, not to fork them.

## Delivered — v0.1.0

The phased build (`docs/SPEC.md` §17) is complete through Phase 4; every gate
passed with human sign-off (`docs/VERIFIED.md`).

| Phase | Content | Status |
|---|---|---|
| **0** | Verification gates 0.1–0.4 (classloader bridge, event inventory, connection-metadata traversal, guacd log format) | Done |
| **1** | Listener extension — session spans, session metrics, auth log records | Done |
| **2** | Collector bundle — journald + hostmetrics + guacd connection-ID correlation (Gap 2) | Done |
| **3** | Grafana dashboard, RPM, install runbook, `PRIVACY.md` (+ Docker & OpenShift packaging) | Done |
| **4** | Load validation and overhead-budget confirmation (p99 well under 1 ms) | Done |

## In progress

### Phase 5 — `guacamolereceiver` (collector-contrib receiver)
An optional, pull-based Go receiver that polls the Guacamole REST API for
active-connection metrics — the ecosystem-native counterpart to the listener and
a replacement for the unmaintained `tschoonj/guacamole_exporter`. A standalone Go
module exists (`receiver/guacamolereceiver/`, status: development). Remaining work
toward an `opentelemetry-collector-contrib` submission:

- Align the module path and generated `internal/metadata` with contrib
  conventions (`mdatagen`).
- Meet contrib's quality bar: `golangci-lint` + `gofmt` clean, `govulncheck`,
  and coverage.
- Expand metric coverage beyond `guacamole.active_connections` where the REST API
  allows it without adding cardinality risk.
- Prepare the upstream contribution (README, ownership, CODEOWNERS entry in
  contrib).

## Long horizon

### Phase 6 — OTLP/metrics endpoint in `guacd` (upstream)
The only durable fix for observing guacd internals is for guacd itself to emit
telemetry. This is an **upstream** direction (a proposal/contribution to Apache
Guacamole), not something this project will fork guacd to achieve. Tracked here
so the boundary is explicit; no timeline.

## Explicitly out of scope

- **Instruction-level / protocol-stream telemetry** (`docs/SPEC.md` §16). A filter
  over the instruction stream is novel code in the data path, and inspecting `key`
  instructions is keystroke logging regardless of intent (DPIA / works-council
  territory). If it is ever built it would be a separate, opt-in, disabled-by-
  default artifact with a strict metadata-only opcode allowlist — see
  `docs/EXTENSION-POINTS.md`. **Recommendation stands: do not build.**
- **Screen/keystroke/clipboard/file *content* capture.** Only connection metadata
  is collected, ever (`docs/PRIVACY.md`).
- **Enforcement or access control.** This project observes; it never gates a login
  or a session.
- **Forking or patching guacamole-client or guacd.**

## Candidate backlog (not committed)

Smaller improvements consistent with the guiding principle. These are ideas, not
promises; contributions and issues that discuss them are welcome.

- **Dashboards & schema:** additional Grafana panels and, where it adds value, a
  documented mapping toward evolving OpenTelemetry semantic conventions (the
  custom `guacamole.*` namespace is intentional — sessions are not RPCs).
- **Supply chain & CI hardening:** static analysis (CodeQL for Java + Go),
  dependency review, SBOM generation for released artifacts, SHA-pinning of all
  actions and container images, and least-privilege workflow permissions — all
  analysis/build only, never auto-publishing (guardrail G10).
- **Collector-side privacy options:** documented pseudonymisation/hashing recipes
  for `enduser.id` and worked retention examples.
- **Broader Guacamole version coverage** beyond 1.6.x as new releases are
  verified (re-running the Phase 0 gates and recording results in
  `docs/VERIFIED.md`).
- **Live agent-bridge boot check in CI** to guard OTel API↔agent version coupling
  (unit tests cannot catch a no-op bridge; see `docs/VERIFIED.md`).

## How to influence the roadmap

Open a GitHub issue describing the use case (see [`SUPPORT.md`](SUPPORT.md)).
Adoption is the main input to prioritisation — especially for Phase 5 and the
candidate backlog.
