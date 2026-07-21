# Governance

This document describes how **guacamole-otel** is governed: who decides what,
how decisions are made, and how people take on more responsibility. It is
deliberately lightweight to match the project's current size, and is written so
it can scale as more maintainers join.

> guacamole-otel is an independent, third-party project. It is **not** affiliated
> with or endorsed by the Apache Software Foundation or the OpenTelemetry
> project. "Apache", "Apache Guacamole", and "Guacamole" are trademarks of the
> Apache Software Foundation.

## Current model: single maintainer (BDFL-lite)

The project is presently maintained by a single maintainer (see
[`MAINTAINERS.md`](MAINTAINERS.md)). Until a second maintainer is added, that
maintainer is the final decision-maker for all changes. This is stated openly so
that adopters can accurately assess the project's bus factor.

The intent is to grow into a small group of maintainers who make decisions by
lazy consensus, with the model below already in place so the transition needs no
renegotiation.

## Roles

- **Contributor** — anyone who opens an issue or a pull request. No formal
  status required. All contributions are under the Apache License 2.0 and must
  carry a DCO sign-off (`git commit -s`); see [`CONTRIBUTING.md`](CONTRIBUTING.md).
- **Maintainer** — has commit/merge authority and is listed in
  [`MAINTAINERS.md`](MAINTAINERS.md). Maintainers are accountable for review,
  releases, and upholding the project's guardrails.

## How decisions are made

The project uses **lazy consensus**:

1. Routine changes (bug fixes, docs, dependency bumps, tests) merge once a
   maintainer approves the pull request and CI is green.
2. Substantial changes (new telemetry, schema changes, new components, new
   workflows, changes to the guardrails) should start as an issue or a short
   proposal in the PR description so there is a record of the rationale.
3. If maintainers disagree, the change is not merged until consensus is reached.
   While there is a single maintainer, that maintainer decides; the reasoning is
   recorded in the issue/PR so it is auditable later.

Design decisions that alter behaviour are recorded in `docs/SPEC.md` and, where
verification is involved, in `docs/VERIFIED.md`. User-visible changes are
recorded in [`CHANGELOG.md`](CHANGELOG.md).

## Scope guardrails (non-negotiable)

Some rules are structural to the project and are **not** subject to ordinary
consensus — a change that violates them will be declined regardless of quality:

- **No novel code in the Guacamole data path.** Instrumentation uses only the
  supported `guacamole-ext` `Listener` API and stock OpenTelemetry Collector
  components. The instruction-filter extension point is deliberately unbuilt
  (`docs/SPEC.md` §16, `docs/EXTENSION-POINTS.md`).
- **The listener never throws.** Any change to the `handleEvent` exception
  envelope in `OtelListener.java` requires maintainer review of the exact block.
- **No credentials in telemetry.** Attributes pass through the allowlist in
  `Attributes.java`; there is no generic serialisation path.
- **Cardinality discipline.** High-cardinality values (tunnel UUIDs, connection
  names, client addresses) are span/log attributes, never metric dimensions.
- **Apache-2.0-compatible dependencies only.** No copyleft.
- **No automation that publishes, pushes, tags, or releases.** CI and any added
  workflows are analysis/build only, run with a least-privilege `permissions:`
  block, and pin every third-party GitHub Action to a full commit SHA. Releases
  and tags are performed deliberately by a maintainer, never automatically.

These are enforced in code and CI where possible, and by review otherwise.

## Releases

Releases follow [Semantic Versioning](https://semver.org/) and are cut manually
by a maintainer. There is no automated publish/tag step. Each release updates
[`CHANGELOG.md`](CHANGELOG.md) and is tagged in git. Supported versions and the
security-fix policy are described in [`SECURITY.md`](SECURITY.md).

## Becoming a maintainer

There is no fixed contribution count. A contributor may be invited to become a
maintainer after a sustained track record of:

- high-quality merged contributions across one or more areas
  (see the area map in [`MAINTAINERS.md`](MAINTAINERS.md));
- sound, respectful review of others' work;
- demonstrated understanding of, and respect for, the scope guardrails above.

Any existing maintainer may nominate a contributor by opening a pull request
that adds them to [`MAINTAINERS.md`](MAINTAINERS.md). With a single maintainer,
that maintainer decides; once there are multiple maintainers, the addition
merges by consensus of the existing maintainers with no objection.

Maintainers who become inactive may move themselves (or be moved by consensus)
to an "Emeritus" list in [`MAINTAINERS.md`](MAINTAINERS.md). Commit access may be
adjusted to match.

## Code of Conduct

All participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).
Enforcement is the responsibility of the maintainers, using the private
reporting channel described there.

## Changing this document

Changes to governance are themselves changes to the project and follow the same
pull-request-and-consensus process. Substantive changes should be raised as an
issue first.
