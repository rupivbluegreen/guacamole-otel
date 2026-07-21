# PRIVACY.md — data inventory & GDPR posture

This extension emits connection **metadata only**. It carries **no session
content** — no keystrokes, no screen data, no clipboard, no transferred files.
That is a direct consequence of the governing principle (observe through event
hooks, never the protocol stream) and the deliberately-unbuilt instruction filter
(`docs/EXTENSION-POINTS.md`, SPEC §16). It is the single most important fact for a
DPIA.

## Attribute inventory

Every attribute the extension can emit is enumerated below. There is no generic
object-to-attributes path in the code (guardrail G3): attributes are built only
from this explicit allowlist (`extension/.../Attributes.java`).

| Attribute | Signals | Personal data? | Notes |
|---|---|---|---|
| `guacamole.tunnel.uuid` | span | no | per-session tunnel UUID; span attribute only, never a metric dimension |
| `guacamole.connection.id` | span, log | no | guacd connection id; the correlation key to guacd logs |
| `guacamole.protocol` | span, log, metric | no | `rdp`/`vnc`/`ssh`/… |
| `guacamole.datasource` | span, log, metric | no | e.g. `postgresql` |
| `enduser.id` | span, log, metric* | **yes** | username. *metric dimension only if `otel.metrics.include-user=true` (default off). Pseudonymisable — see below |
| `client.address` | span, log | **yes** | client remote address; never a metric dimension |
| `guacamole.session.end_reason` | span, metric | no | `closed` \| `timeout` |
| `guacamole.auth.outcome` | log, metric | no | `success` \| `failure` |
| `guacamole.auth.failure_type` | log | no | the failure Throwable's **class name** only, never its message |
| `event.name` | log | no | e.g. `guacamole.session.connected`, `guacamole.auth.success` |
| `guacamole.otel.stage` | metric | no | self-telemetry |
| `guacamole.otel.registry.reason` | metric | no | self-telemetry (`capacity` \| `timeout`) |

**Never captured, under any configuration:** the `Credentials` object,
`getPassword()`, session tokens, request headers, or any field not listed above.
A unit test asserts that no emitted attribute value equals the test password (G3).

## Pseudonymisation (§10.2)

`enduser.id` and `client.address` are personal data under GDPR. Two hooks; default
is **identifiable** (regulated deployments flip it):

1. **Extension-level.** Set in `guacamole.properties`:
   ```properties
   otel.attributes.hash-user=true
   otel.attributes.hash-user-salt=<a-long-random-secret>   # never logged
   ```
   `enduser.id` becomes a salted SHA-256 digest. The same user hashes stably, so
   per-user analysis survives; the salt is never emitted.

2. **Collector-level (preferred).** Keeps the policy where the rest of the estate's
   data handling lives, and lets on-host telemetry stay identifiable while exported
   telemetry is pseudonymised. Uncomment in `collector/otelcol-guacamole.yaml`:
   ```yaml
   processors:
     attributes/pseudonymise:
       actions:
         - key: enduser.id
           action: hash
   ```
   and add `attributes/pseudonymise` to the `logs` and `traces` pipelines.
   To drop `client.address` on export instead, use `action: delete`.

## Retention

Out of scope for the extension — a backend concern. Retention **must be set
explicitly**: access logs that constitute audit evidence typically carry a defined
minimum *and* maximum retention. Set it on your traces/logs backend.

## Non-repudiation caveat (SPEC §11, verbatim)

> this telemetry is *operational*, not tamper-evident. It is not a substitute for
> a WORM audit store, and the collector pipeline is not an evidential chain of
> custody. Positioning it otherwise would be an audit finding.
