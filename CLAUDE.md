# CLAUDE.md — guacamole-otel

OpenTelemetry instrumentation for Apache Guacamole. Listener extension (Java) +
OTel Collector configuration bundle. Apache-2.0. The authoritative design is
`docs/SPEC.md`; the build order is `docs/BUILD-PHASES.md`. When this file and a
prompt conflict, this file wins. When this file and SPEC.md conflict, stop and
ask.

## Governing principle

**No novel code in the data path.** This project observes Guacamole through its
supported event hooks (`guacamole-ext` Listener API) and through stock OTel
Collector components. It never intercepts, filters, or modifies the Guacamole
protocol stream. SPEC.md §16 documents the instruction-filter extension point as
deliberately unbuilt — do not implement it, do not "prototype" it, do not add
dependencies that anticipate it.

## Hard guardrails — never violate, never "temporarily" relax

G1. **`handleEvent` never propagates.** The outermost construct in
    `OtelListener.handleEvent` is `try { … } catch (Throwable t)`. A listener
    exception vetoes the Guacamole action in progress (denies a login, denies a
    connection). Never narrow `Throwable` to `Exception`. Never remove the
    catch to "see the real error" — use the suppressed-error debug log and the
    `guacamole.otel.errors` counter instead. Any diff touching this block
    requires the human to approve the exact new block verbatim.

G2. **No policy enforcement.** This extension observes. It never throws to
    veto, never blocks, never rate-limits, never denies. If a task asks for
    enforcement behaviour, refuse and flag: that belongs in a separate
    extension with its own review.

G3. **Credentials never touch telemetry.** Never serialise the `Credentials`
    object or call `getPassword()`. Attributes are built only through the
    explicit allowlist in `Attributes.java`. Never add a generic
    object-to-attributes, toString-based, or reflection-based attribute path.
    The unit test asserting no attribute value equals the test password must
    never be weakened or deleted.

G4. **Metric cardinality allowlist is a code invariant.** `guacamole.tunnel.uuid`,
    `guacamole.connection.name`, and `client.address` are span/log attributes
    only — the metric-dimension builder must make them unattachable at the type
    level, and the unit test asserting this must stay. `enduser.id` on metrics
    is opt-in via config, default off.

L5. **Zero blocking I/O on the event thread.** No network calls, no synchronous
    exports, no unbounded lock waits inside `handleEvent`. Exporters are async
    with bounded queues (`BatchSpanProcessor`, never `SimpleSpanProcessor`).
    Directory lookups (protocol resolution) go through the bounded TTL cache;
    on miss, emit `protocol=unknown` rather than block.

G6. **SessionRegistry stays bounded.** Hard max entries + TTL sweep. Never
    remove the bound, never make eviction unconditional retention "so we don't
    lose spans". Restart-lossy is by design.

G7. **Do not touch guacd, do not fork guacamole-client.** No patches, no
    submodules of Guacamole source, no rebuilds of the webapp. `guacamole-ext`
    and `guacamole-common` are `provided`-scope dependencies only.

G8. **Verify before claiming.** SPEC.md Appendix B lists eight unverified API
    claims. Any code that depends on one must first resolve it against the
    actual 1.6.x javadoc/source (fetch it; do not rely on training memory) and
    record the answer in `docs/VERIFIED.md` with the source URL. Never mark a
    Phase 0 gate as passed without command output or fetched documentation
    proving it.

G9. **Licensing discipline.** Apache-2.0 only. Every dependency added must be
    Apache-2.0/MIT/BSD-compatible; no GPL/AGPL/LGPL, no copied code from
    Stack Overflow or blogs. New source files carry the ASF-style header from
    `docs/HEADER.txt`. Update NOTICE when adding a dependency that requires it.

G10. **Human gates.** The following are proposed as a plan and executed only
     after explicit human approval in the session:
     - any `git push`, release tag, or GitHub release
     - publishing artefacts anywhere (Maven, package repos)
     - deleting or force-moving files; any `rm -rf`; history rewrites
     - version bumps of `guacamole-ext` or the OTel BOM
     - marking a Phase exit gate as passed
     - any change to this file

## Build discipline

- Phases execute strictly in order (`docs/BUILD-PHASES.md`). Do not start
  Phase N+1 work — including "harmless" scaffolding — before Phase N's exit
  gate is recorded as passed in `docs/VERIFIED.md`.
- Each phase isolates one failure class. If a failure belongs to an earlier
  phase's class, stop and reopen that phase; do not patch around it downstream.
- Every commit: `mvn -q verify` green first. No commits with failing or
  skipped tests; never add `-DskipTests` to any script or doc.
- Conventional commits (`feat:`, `fix:`, `test:`, `docs:`, `build:`).
  DCO sign-off (`git commit -s`) on every commit.
- Small diffs. One concern per commit. No drive-by refactors inside a task.

## Environment facts

- Target: Guacamole 1.6.x, Java 17 (match Guacamole's build baseline — verify,
  G8), Maven, RHEL9 for the RPM (`nfpm`).
- Integration environment is docker-compose (`itest/`): guacamole, guacd,
  postgres, otel-collector, plus a throwaway SSH target. Never point tests at
  a real estate.
- OTel: pin via `opentelemetry-bom`. Standard `OTEL_*` env vars are the only
  exporter configuration in the agent-bridge branch.

## Definition of done, per task

1. Code + tests written, `mvn -q verify` green.
2. Guardrails G1–G9 checked against the diff, explicitly, in the session.
3. `docs/VERIFIED.md` updated if any Appendix-B claim was touched.
4. Commit message written; push only through G10.
