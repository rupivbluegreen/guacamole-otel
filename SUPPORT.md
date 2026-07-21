# Getting help

Thanks for using **guacamole-otel** — OpenTelemetry instrumentation for Apache
Guacamole. This page explains where to go for each kind of request so you get an
answer as quickly as possible.

> guacamole-otel is an independent, third-party project and is **not** affiliated
> with or endorsed by the Apache Software Foundation or the OpenTelemetry
> project. For help with **Guacamole itself** (not this instrumentation), use the
> [Apache Guacamole community](https://guacamole.apache.org/support/).

## Start with the docs

Most questions are answered by the project documentation:

- **[README.md](README.md)** — what the project does and a Docker quickstart.
- **[docs/INSTALL.md](docs/INSTALL.md)** — full install on RHEL9/RPM, Docker, and
  OpenShift/Kubernetes.
- **[docs/PRIVACY.md](docs/PRIVACY.md)** — what data is and isn't collected, and
  pseudonymisation.
- **[docs/SPEC.md](docs/SPEC.md)** — the design, telemetry schema, and the
  reasoning behind scope boundaries.
- **[docs/VERIFIED.md](docs/VERIFIED.md)** — the verification log (what was
  probe-confirmed against Guacamole 1.6.x).
- **[docs/EXTENSION-POINTS.md](docs/EXTENSION-POINTS.md)** — the deliberately
  unbuilt instruction-filter extension point and why.
- **Component notes** — [`receiver/guacamolereceiver/README.md`](receiver/guacamolereceiver/README.md)
  and [`packaging/openshift/README.md`](packaging/openshift/README.md).

## Where to ask

| I want to… | Go here |
|---|---|
| Ask a question or get usage help | Open a **GitHub issue** on this repository. Please include your Guacamole version, which component is involved (extension / collector bundle / Go receiver / packaging), and your OpenTelemetry agent and Collector versions. |
| Report a bug | Open a **GitHub issue** with steps to reproduce, expected vs actual behaviour, and relevant logs (with any credentials/PII redacted). |
| Request a feature or discuss scope | Open a **GitHub issue** describing the use case. Note that the project intentionally limits scope — see [`ROADMAP.md`](ROADMAP.md) and `docs/SPEC.md` §16 for what is out of scope and why. |
| Report a **security vulnerability** | **Do not open a public issue.** Follow [`SECURITY.md`](SECURITY.md) — report privately via GitHub Security Advisories. |
| Report a Code of Conduct concern | Use the private channel in [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md). |
| Contribute a change | Read [`CONTRIBUTING.md`](CONTRIBUTING.md) (DCO sign-off, tests, guardrails) first. |

## What to include in a good report

Including these up front usually saves a round-trip:

- **Component**: extension (Java listener), collector bundle, Go receiver, or
  packaging (RPM/Docker/OpenShift).
- **Versions**: guacamole-otel version, Apache Guacamole version (1.6.x
  supported), OpenTelemetry Java agent version, and OpenTelemetry Collector
  version.
- **Environment**: RHEL9/RPM, Docker, or Kubernetes/OpenShift.
- **Logs**: relevant Collector, guacd, or Tomcat/Guacamole log lines — with
  usernames, addresses, and any secrets redacted.

## Support expectations

This is a community project maintained on a best-effort basis (see
[`MAINTAINERS.md`](MAINTAINERS.md)). There is no commercial support or SLA. The
security-report acknowledgement target is stated in [`SECURITY.md`](SECURITY.md);
general issues are handled as maintainer time allows.
