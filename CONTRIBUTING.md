# Contributing to guacamole-otel

Contributions are welcome under the Apache License 2.0. This is an independent
project and is **not affiliated with or endorsed by the Apache Software
Foundation** or the OpenTelemetry project.

Please also read the [Code of Conduct](CODE_OF_CONDUCT.md). For questions and
help (as opposed to bug reports), see [SUPPORT.md](SUPPORT.md).

## Developer Certificate of Origin

This project uses the DCO (https://developercertificate.org/) rather than a
CLA. Sign every commit:

    git commit -s

The `-s` flag appends a `Signed-off-by:` trailer certifying you wrote the patch
or have the right to submit it. Unsigned commits are rejected by CI.

## Development prerequisites

You do **not** need a local JDK or Maven install — the extension builds inside a
container. You need:

- **Docker** and **Docker Compose** — for the containerised Maven build, the
  collector-config validation, and the integration test.
- **Go 1.25** — only if you work on the `receiver/` collector receiver.
- **curl** — used by the integration test harness.

The extension targets Guacamole 1.6.x and is compiled at
`maven.compiler.release=8`. Any JDK ≥ 8 builds it (CI uses Temurin 17); the
containerised commands below pin the toolchain for you.

## Building and testing

### Extension (Java, `extension/`)

Build, run the JUnit 5 suite, and enforce the license header in one step. `mvn
verify` also runs the `license-maven-plugin` header check, so a missing header
fails the build:

    docker run --rm -v "$PWD/extension":/w -w /w \
      maven:3.9-eclipse-temurin-17 mvn -q -B verify

`mvn verify` must be green before you push. Never add `-DskipTests` to any
script, command, or doc.

### Receiver (Go, `receiver/guacamolereceiver/`)

From `receiver/guacamolereceiver/`:

    go build ./...
    go vet ./...
    go test ./...

Style and static analysis are configured in
[`receiver/guacamolereceiver/.golangci.yml`](receiver/guacamolereceiver/.golangci.yml)
(gofmt, goimports, govet, staticcheck, errcheck, ineffassign, revive). Run it
with [golangci-lint](https://golangci-lint.run/) v2:

    golangci-lint run

### Collector config (`collector/`)

Validate the stock collector bundle against opentelemetry-collector-contrib:

    docker run --rm -e OTLP_EXPORTER_ENDPOINT=localhost:4317 \
      -v "$PWD/collector":/cfg:ro \
      otel/opentelemetry-collector-contrib validate --config /cfg/otelcol-guacamole.yaml

### Integration test (`itest/`)

The correlation + failure-injection test brings up guacamole, guacd, postgres,
and the collector, drives one session, and asserts that guacd log lines join the
listener's session telemetry. Build the extension jar first (see the header of
the script), then:

    cd itest
    ./run-correlation-test.sh

## License headers

Every new `.java` and `.go` **source** file must carry the Apache-2.0 header
from [`docs/HEADER.txt`](docs/HEADER.txt) (adapted to the language's comment
syntax). This is the standard Apache-2.0 boilerplate and does not imply any ASF
affiliation. For Java the header is enforced by `mvn verify`. Governance,
Markdown, YAML, and other config files do **not** need the header.

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
- PRs without tests for behavioural changes are not merged.

## Commit conventions

- **Conventional Commits.** Prefix each commit with its type:
  `feat:`, `fix:`, `docs:`, `chore:`, `test:`, `build:`, `ci:`.
- **One concern per PR**, small diffs preferred.
- Every commit is signed off (`git commit -s`, see DCO above).

## Pull-request workflow

1. Open an issue describing the change before large PRs.
2. Fork, create a branch, and make focused commits (conventional + signed off).
3. Ensure `mvn verify` (extension) and `go test ./...` + `golangci-lint run`
   (receiver, if touched) are green, with tests for any behavioural change.
4. Open the PR and complete the checklist in the pull-request template.
5. A maintainer reviews it (see [MAINTAINERS.md](MAINTAINERS.md) and
   [GOVERNANCE.md](GOVERNANCE.md)). Changes to the `OtelListener.handleEvent`
   exception envelope require explicit maintainer sign-off on the exact block.

## Security

Do not open public issues for vulnerabilities — see [SECURITY.md](SECURITY.md).
