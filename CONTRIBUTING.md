# Contributing to guacamole-otel

Contributions are welcome under the Apache License 2.0.

## Developer Certificate of Origin

This project uses the DCO (https://developercertificate.org/) rather than a
CLA. Sign every commit:

    git commit -s

Unsigned commits are rejected by CI.

## Ground rules

- **No novel code in the data path.** This project instruments Guacamole
  through the supported `guacamole-ext` Listener API and stock OpenTelemetry
  Collector components only. PRs that intercept or filter the Guacamole
  protocol stream, patch guacd, or fork guacamole-client will be declined —
  see docs/SPEC.md §16 for why the instruction-filter extension point is
  deliberately unbuilt.
- **The listener never throws.** Any change to the exception envelope in
  `OtelListener.handleEvent` requires maintainer review of the exact block.
- **No credentials in telemetry, ever.** Attributes go through the allowlist
  in `Attributes.java`; there is no generic serialisation path and none will
  be accepted.
- **Cardinality:** tunnel UUIDs, connection names, and client addresses are
  span/log attributes, never metric dimensions.
- Dependencies must be Apache-2.0/MIT/BSD-compatible. No copyleft.
- All source files carry the header in `docs/HEADER.txt`.
- `mvn verify` must be green; PRs without tests for behavioural changes are
  not merged.

## Workflow

1. Open an issue describing the change before large PRs.
2. Fork, branch, conventional commits (`feat:`, `fix:`, `test:`, `docs:`).
3. One concern per PR, small diffs preferred.

## Security

Do not open public issues for vulnerabilities — see SECURITY.md.
