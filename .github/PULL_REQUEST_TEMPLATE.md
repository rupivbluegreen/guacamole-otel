<!--
Thanks for contributing to guacamole-otel (Apache-2.0). Please fill in this
template. Small, single-concern PRs are reviewed fastest. See CONTRIBUTING.md.
This is an independent project — not affiliated with or endorsed by the
Apache Software Foundation or the OpenTelemetry project.
-->

## Summary

<!-- What does this PR change, and why? Link the issue it addresses. -->

Closes #

## Type of change

<!-- Check all that apply. The PR title should follow Conventional Commits
     (feat: / fix: / docs: / test: / build: / ci: / chore:). -->

- [ ] `feat:` new capability
- [ ] `fix:` bug fix
- [ ] `docs:` documentation only
- [ ] `test:` tests only
- [ ] `build:` / `ci:` build, packaging, or workflow change
- [ ] `chore:` maintenance / refactor (no behaviour change)

## Affected component

- [ ] extension (Java guacamole-ext listener)
- [ ] collector (OpenTelemetry Collector config + systemd unit)
- [ ] receiver (guacamolereceiver Go scraper)
- [ ] packaging (RPM / Docker / OpenShift / Kubernetes)
- [ ] dashboards / docs / itest

## Checklist

<!-- All boxes must be checked (or marked N/A) before review. -->

- [ ] **DCO:** every commit is signed off with `git commit -s` (`Signed-off-by:` line). Unsigned commits are rejected by CI.
- [ ] **Conventional Commits:** the PR title and commits use `feat:` / `fix:` / `docs:` / `test:` / `build:` / `ci:` / `chore:`.
- [ ] **Tests:** behavioural changes include tests; I did not weaken existing tests.
- [ ] **License header:** any new `.java` / `.go` source file carries the header from `docs/HEADER.txt`.
- [ ] **Dependencies:** any new dependency is Apache-2.0 / MIT / BSD-compatible (no copyleft).
- [ ] **No secrets in telemetry:** no credentials, tokens, or new un-allowlisted attributes are emitted; attributes still go through the `Attributes.java` allowlist.
- [ ] **Cardinality:** tunnel UUIDs, connection names, and client addresses remain span/log attributes, never metric dimensions.
- [ ] **Data path:** this change stays on the supported guacamole-ext / stock OpenTelemetry path (no protocol interception, guacd patching, or guacamole-client fork — see `docs/SPEC.md` §16).
- [ ] **Listener safety:** if this touches `OtelListener.handleEvent`, I called out the exact exception-envelope block for maintainer review (the listener must never throw).
- [ ] **Docs updated:** README / `docs/` (INSTALL, SPEC, PRIVACY, VERIFIED, EXTENSION-POINTS) updated if behaviour, config, or interfaces changed — or N/A.

## How was this tested?

<!-- Show the commands you ran and their outcome. Include what applies: -->

- [ ] `mvn -f extension/pom.xml verify` (extension: build + tests + license-header check)
- [ ] `go build ./... && go vet ./... && go test ./...` in `receiver/guacamolereceiver` (receiver)
- [ ] `otelcol-contrib validate --config collector/otelcol-guacamole.yaml` (collector config)
- [ ] `itest/` integration test (correlation + failure injection)

```
# paste relevant command output here
```

## Additional notes

<!-- Anything reviewers should know: trade-offs, follow-ups, screenshots. -->
