# Changelog

All notable changes to **guacamole-otel** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Releases are cut manually by a maintainer; there is no automated publish or tag
step (see [`GOVERNANCE.md`](GOVERNANCE.md)).

## [Unreleased]

### Added
- **Go receiver `guacamolereceiver`** (Phase 5) — a stock-component
  OpenTelemetry Collector scraper receiver (Go 1.25, module
  `github.com/guacamole-otel/guacamolereceiver`) that polls the Guacamole REST
  API and reports the `guacamole.active_connections` gauge. Pull-based,
  ecosystem-native counterpart to the listener extension; standalone module,
  prepared for an `opentelemetry-collector-contrib` submission. Status:
  development.
- **Dependabot configuration** (`.github/dependabot.yml`) for Maven, Go modules,
  GitHub Actions, and Docker.
- **`package-build` CI job** that builds the RPM as part of continuous
  integration.
- **Go receiver CI** (`.github/workflows/go.yml`) — build, vet, test, plus
  golangci-lint, govulncheck, and coverage.
- **OSS maturity set** — `CODE_OF_CONDUCT.md`, `GOVERNANCE.md`, `MAINTAINERS.md`,
  `SUPPORT.md`, `.github/CODEOWNERS`, `ROADMAP.md`, issue/PR templates.
- **Security scanning** — CodeQL (Java + Go), OpenSSF Scorecard, and a
  dependency-review gate; workflows hardened with least-privilege `permissions`
  and SHA/digest-pinned actions + images.
- **Release publishing** (`release.yml`) — on a `v*` tag, builds and attaches the
  jar + RPM + CycloneDX SBOM + `SHA256SUMS` + keyless cosign signatures to a
  GitHub Release.
- **Supply chain & quality** — CycloneDX SBOM, JaCoCo coverage (~85% line), and a
  reproducible-build timestamp for the extension.
- **`docs/PERFORMANCE.md`** — measured overhead (per-event p99 ≈ 20 µs, no
  measurable added login latency) against real Guacamole.

### Changed
- Rewrote `README.md` in plain language (what you get, what it deliberately does
  not do, install matrix, compliance mapping).
- Normalized `LICENSE` so Apache-2.0 is detected correctly by GitHub/linguist.
- Applied safe Dependabot dependency bumps. The `opentelemetry-bom` is
  intentionally held to patch-only and bumped **only in lockstep with the OTel
  Java agent**: a minor/major API bump against an older agent silently resolves
  `GlobalOpenTelemetry` to a no-op and drops all `guacamole.*` telemetry with no
  error (see `docs/VERIFIED.md`, "otel-api ↔ agent bridge version coupling").

### Fixed
- `collector-validate` CI step now uses the pinned collector-contrib Docker image
  instead of an unavailable binary.
- DCO sign-off check inlined in CI so it runs without an external dependency.

## [0.1.0] - 2026-07-21

Initial release. Verified end-to-end against Apache Guacamole 1.6.x (runtime JVM
21; extension built to Java 8 bytecode). All Phase 0–4 gates passed with
human sign-off; evidence is recorded in `docs/VERIFIED.md`.

### Added

**Extension — Java `guacamole-ext` listener (`extension/`)**
- `OtelListener`, a Guacamole `Listener` that emits telemetry from the four
  public lifecycle events (`AuthenticationSuccessEvent`,
  `AuthenticationFailureEvent`, `TunnelConnectEvent`, `TunnelCloseEvent`) via
  `instanceof` dispatch.
- **Traces:** one `guacamole.session` SERVER span per session, carrying
  `guacamole.connection.id` — the guacd correlation key read directly from the
  `ConfiguredGuacamoleSocket` at connect time (protocol + connection id, zero
  blocking I/O, no directory lookup or TTL cache).
- **Metrics:** session-started/active/duration and authentication-outcome
  instruments, with cardinality held independent of session count (protocol and
  data source only; UUIDs, connection names, and client addresses are never
  metric dimensions).
- **Logs:** structured auth-success/failure and session start/stop records;
  periodic token re-auth (`isExistingSession()`) and empty-credential
  login-screen hits are filtered as noise.
- **Guardrails:** the listener never throws — `handleEvent` swallows every error,
  so a telemetry failure cannot block a Guacamole login or session (G1); a strict
  attribute allowlist means no credentials ever reach telemetry (G3), and
  `getPassword()` is never called; the session registry is bounded by capacity
  and TTL (G6).
- **Agent-bridge integration:** `opentelemetry-api` + `opentelemetry-context` are
  shaded into the extension jar (compile scope); the SDK, exporters, and all
  configuration come from the deployed OpenTelemetry Java agent via
  `GlobalOpenTelemetry`.
- JUnit 5 test suite covering span/metric/log behaviour, the no-throw envelope,
  the no-credential and cardinality guarantees, registry bounding, and a
  per-event overhead benchmark (p99 well under the 1 ms budget).

**Collector bundle (`collector/`)**
- `otelcol-guacamole.yaml` for `opentelemetry-collector-contrib`: journald +
  hostmetrics receivers, the **Gap 2 correlation** transform that extracts the
  guacd connection id (`Connection ID is "$..."`) so guacd log lines and session
  telemetry share one `guacamole.connection.id`, and cardinality/privacy
  processors.
- systemd unit and environment file for running the collector as a service.

**Packaging (`packaging/`)**
- **RPM** built with nfpm — a drop-in jar to
  `/etc/guacamole/extensions/`, no scriptlets; verified clean install/remove on
  Rocky Linux 9.
- **Docker** — instrumented Guacamole image (plugin + OTel agent baked in), a
  collector image, and a `docker-compose.yml`.
- **OpenShift/Kubernetes** — manifests (SCC / arbitrary-UID safe) with a
  ConfigMap-based collector config and an example secrets file.

**Dashboards (`dashboards/`)**
- Grafana starter dashboard (`guacamole-overview.json`).

**Integration test (`itest/`)**
- docker-compose harness validating guacd↔session correlation and failure
  injection (collector killed mid-session, collector down from start, exporter
  throwing) — in every case Guacamole login and session availability are
  unaffected.

**Documentation (`docs/`)**
- `SPEC.md` (design + telemetry schema), `BUILD-PHASES.md`, `VERIFIED.md`
  (verification log), `INSTALL.md`, `PRIVACY.md`, `EXTENSION-POINTS.md`, and the
  ASF-style license header (`HEADER.txt`).

**Project & CI**
- Apache-2.0 `LICENSE`, `NOTICE`, `README.md`, `CONTRIBUTING.md`, `SECURITY.md`.
- CI (`.github/workflows/ci.yml`) — build, test, collector-config validation,
  DCO sign-off check, and license-header enforcement (via `mvn verify`).
- `release.yml` is **draft/`workflow_dispatch`, build-only** — it never publishes,
  pushes, tags, or releases.

### Security
- Telemetry contains access metadata (usernames, source addresses, connection
  names) and is operational data, not a tamper-evident audit trail; transport and
  storage guidance is in `SECURITY.md` and `docs/PRIVACY.md`.
- The instruction-level (protocol-stream) extension point is **deliberately not
  built** — it would be novel code in the data path and, for `key` instructions,
  keystroke logging. See `docs/SPEC.md` §16 and `docs/EXTENSION-POINTS.md`.

[Unreleased]: https://github.com/rupivbluegreen/guacamole-otel/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rupivbluegreen/guacamole-otel/releases/tag/v0.1.0
