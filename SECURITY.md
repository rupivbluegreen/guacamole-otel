# Security Policy

## Reporting

Report vulnerabilities privately via GitHub Security Advisories for this
repository. Do not open public issues for security reports. Expect an
acknowledgement within 72 hours.

## Scope notes

- This extension observes authentication and session lifecycle events; it
  enforces nothing. A compromise of the telemetry pipeline does not grant
  access to Guacamole sessions, but telemetry contains access metadata
  (usernames, source addresses, connection names) and should be transported
  and stored accordingly (localhost OTLP + TLS beyond the host).
- Telemetry is operational data, not a tamper-evident audit trail.
