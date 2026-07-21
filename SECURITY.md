# Security Policy

guacamole-otel is an independent, third-party project that provides
OpenTelemetry instrumentation for Apache Guacamole. It is **not** affiliated
with, endorsed by, or a product of the Apache Software Foundation (which
maintains Apache Guacamole) or the OpenTelemetry project. This policy covers
only the code in *this* repository:

- vulnerabilities in **Apache Guacamole** itself go to the Apache Software
  Foundation (<https://guacamole.apache.org/security/>);
- vulnerabilities in the **OpenTelemetry** Collector, Java agent, or SDKs go to
  their respective upstream projects.

## Supported versions

The project is pre-1.0. Security fixes are provided only for the most recent
release line, and are shipped as new patch releases on that line. The current
release line is `0.1.x`.

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |
| < 0.1.0 | :x:                |

Because there is a single active release line today, **"upgrade to the latest
`0.1.z`"** is the standard remediation. When a `0.2.0` (or later) line is cut,
this table will be updated to record which series still receive fixes.

## Reporting a vulnerability

**Report privately. Do not open a public issue, pull request, or discussion for
a suspected vulnerability, and do not disclose it publicly until a fix is
available.**

Use GitHub's private vulnerability reporting for this repository:

1. Open the repository's **Security** tab → **Advisories** → **Report a
   vulnerability**, or go directly to
   <https://github.com/rupivbluegreen/guacamole-otel/security/advisories/new>.
2. Fill in the report (see "What to include" below).

This creates a private security advisory visible only to you and the
maintainers. GitHub private reporting is the only supported intake channel; the
project deliberately does not publish a security email address. If you are
unable to use private reporting at all, open a *minimal* public issue that says
only "I would like to report a security issue privately" — with **no** details —
and a maintainer will open a private advisory to continue.

### What to include

- **Affected component**: extension (Java listener), collector config bundle,
  Go receiver (`guacamolereceiver`), packaging (RPM / Docker / OpenShift /
  Kubernetes), or docs.
- **Version or commit**: the release (`0.1.z`) or git commit you tested.
- **Environment**: Guacamole version, OpenTelemetry agent/collector versions,
  OS/JDK/Go as relevant.
- **Reproduction**: steps or a proof of concept, the observed impact, and — if
  known — a suggested fix or mitigation.

## Coordinated disclosure and response targets

This is a single-maintainer project (see `MAINTAINERS.md`). The following are
good-faith targets, not contractual SLAs:

| Stage                              | Target                                               |
| ---------------------------------- | ---------------------------------------------------- |
| Acknowledge the report             | within 72 hours                                      |
| Initial assessment + severity      | within 7 days                                        |
| Fix or documented mitigation       | within 30 days for High/Critical; best effort otherwise |
| Coordinated public disclosure      | after a fix or mitigation is available; default embargo up to 90 days |

We practice **coordinated disclosure**: please give us a reasonable opportunity
to release a fix before disclosing publicly. During the embargo we will keep you
updated on progress, credit you in the published advisory unless you ask to
remain anonymous, and — where warranted — request a CVE through GitHub's advisory
process. If a report is out of scope or not reproducible, we will explain why.

## Scope

**In scope**

- The extension, collector config, Go receiver, packaging, and manifests in this
  repository.
- Issues such as: credentials or secrets leaking into telemetry; injection into
  or spoofing of the telemetry pipeline; defeating the listener's fail-closed
  guarantee (see below); or privilege escalation via the packaged artifacts
  (RPM/Docker/systemd/Kubernetes).

**Out of scope**

- Apache Guacamole, the OpenTelemetry Collector/agent/SDK upstreams, Grafana,
  and any other third-party software — report those to their maintainers.
- Findings that require an already-compromised host or already-elevated
  privileges.
- Operator responsibilities that are documented as such — e.g. terminating TLS
  on OTLP beyond localhost (see the security-model notes below and
  `docs/PRIVACY.md`).

### Security-model notes

- This extension **observes** authentication and session lifecycle events; it
  enforces nothing. A compromise of the telemetry pipeline does not grant access
  to Guacamole sessions. However, telemetry contains access metadata (usernames,
  source addresses, connection names) and must be transported and stored
  accordingly: localhost OTLP by default, with TLS applied for any hop beyond the
  host.
- Telemetry is operational data, **not** a tamper-evident audit trail.
- The listener is designed to **fail closed for Guacamole**: it must never throw
  into Guacamole's event path (guardrail G1) and must never place credentials or
  secrets into telemetry attributes (guardrails G3/G4). Reports that defeat
  either property are in scope and treated as high severity.

## Do not include secrets in reports

Redact real credentials, tokens, private keys, session cookies, and personal
data from reports, logs, screenshots, and proofs of concept — replace them with
obviously fake placeholders. If a real secret has already been exposed, **rotate
it** and simply tell us a rotation was needed rather than pasting the value. The
maintainers will never ask you to share a working credential.

## Safe harbor

We support good-faith security research. If you make a good-faith effort to
follow this policy, we will consider your research authorized, will not pursue or
support legal action against you for it, and will work with you to resolve the
issue promptly.

Good faith means: test only against installations you own or are explicitly
authorized to test; do not access, modify, or destroy data that is not yours; do
not degrade service for others (no denial-of-service, spam, or social
engineering); stay within the scope above; avoid privacy violations; and give us
a reasonable time to remediate before any public disclosure.

This is **not** a paid bug-bounty program — there is no monetary reward — and
nothing in this section waives the rights of, or authorizes testing against, any
third party (including the Apache Software Foundation or the OpenTelemetry
project).

## Artifact integrity

Released artifacts (the extension jar, RPM, and container/Kubernetes packaging)
are produced by build-only CI; the project never auto-publishes, pushes, tags,
or releases (guardrail G10). If you cannot verify an artifact's integrity, or you
believe a distributed artifact does not match the source in this repository,
treat it as a security report and use the private reporting channel above.
