# Maintainers

This file is the authoritative list of maintainers for **guacamole-otel**. It
exists so that adopters can see who is accountable for the project and how
review is routed. For *how* maintainers make decisions and how people become
maintainers, see [`GOVERNANCE.md`](GOVERNANCE.md).

> guacamole-otel is an independent, third-party project. It is **not** affiliated
> with or endorsed by the Apache Software Foundation (which maintains Apache
> Guacamole) or the OpenTelemetry project.

## Current maintainers

| Maintainer | GitHub | Areas |
|---|---|---|
| Project lead | [@rupivbluegreen](https://github.com/rupivbluegreen) | All areas — final review authority |

Review is also routed automatically via [`.github/CODEOWNERS`](.github/CODEOWNERS).

## Areas of the codebase

The project is small but spans four languages/toolchains. Ownership today is
consolidated under the single maintainer; the columns below record the intended
areas so that additional maintainers can be onboarded per area as the community
grows.

| Area | Path | Notes |
|---|---|---|
| Extension (Java listener) | `extension/` | guacamole-ext `Listener`; Java 8 bytecode target |
| Collector bundle | `collector/` | OpenTelemetry Collector config + systemd unit |
| Go receiver | `receiver/guacamolereceiver/` | collector-contrib scraper receiver |
| Packaging | `packaging/` | nfpm RPM, Docker, OpenShift/Kubernetes |
| Dashboards | `dashboards/` | Grafana JSON |
| Integration test | `itest/` | docker-compose correlation + failure injection |
| Docs & governance | `docs/`, repo-root `*.md`, `.github/` | SPEC, install, privacy, community health |

## Change-sensitive areas (maintainer review required)

Some changes require explicit maintainer review of the exact diff, per the rules
in [`CONTRIBUTING.md`](CONTRIBUTING.md). These are flagged in `.github/CODEOWNERS`:

- `extension/src/main/java/org/apache/guacamole/otel/OtelListener.java` — the
  `handleEvent` exception envelope (the listener must never throw, guardrail G1).
- `extension/src/main/java/org/apache/guacamole/otel/Attributes.java` — the
  telemetry attribute allowlist (no credentials in telemetry, guardrails G3/G4).

## Bus factor

The project currently has a **single maintainer**. This is recorded openly
rather than hidden: adopters should factor it into their risk assessment. The
project mitigates this by keeping the design fully documented in `docs/SPEC.md`
and `docs/VERIFIED.md`, using only supported, upstream extension points (no forks
or patches), and welcoming new maintainers via the path in
[`GOVERNANCE.md`](GOVERNANCE.md).

## Becoming a maintainer

Sustained, high-quality contribution is the path to maintainership. See
[`GOVERNANCE.md`](GOVERNANCE.md#becoming-a-maintainer) for the specifics.

## Emeritus maintainers

None yet.
