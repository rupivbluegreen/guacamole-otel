# EXTENSION-POINTS.md — instruction-level telemetry (specified, NOT built)

Documented so the boundary is deliberate rather than discovered. This is SPEC §16.
**It is not implemented, and must not be** — see the governing principle in
`CLAUDE.md`.

## Mechanism (verified reachable, 2026-07-21)

The stock building blocks exist in `guacamole-ext` / `guacamole-common` 1.6.0
(VERIFIED, `docs/VERIFIED.md` Appendix B #7):

- Subclass `DelegatingUserContext` (exposes `getConnectionDirectory()`), intercept
  `Connection.connect()`, and wrap the returned `GuacamoleTunnel`'s socket in a
  `FilteredGuacamoleSocket(GuacamoleSocket, GuacamoleFilter readFilter,
  GuacamoleFilter writeFilter)`.
- `GuacamoleFilter.filter(GuacamoleInstruction)` then runs on **every instruction
  in both directions**.

The classes are stock; the filter body is not, and it sits in the data path.

## Why it is deferred

1. It is **novel code in the data path** — a direct violation of the governing
   principle, and a latency and correctness risk on every frame.
2. The high-value opcodes for compliance (`clipboard`, `file`) are already captured
   by Guacamole's built-in session recording.
3. A filter observing `key` instructions is **keystroke logging** regardless of
   intent — DPIA and works-council territory, not observability.

## Constraints if it were ever built (separate artefact, separate review)

- Separate jar, disabled by default, explicit opt-in.
- Strict opcode **allowlist**: `size`, `clipboard`, `file`, `disconnect`.
- `key`, `mouse`, `blob`, `img`, `sync` **never** inspected — they are both the
  high-rate opcodes and the privacy landmines.
- Metadata only (that a clipboard event occurred, and its size); never content.

**Recommendation: do not build.** Ship v1 and see whether anyone asks.
