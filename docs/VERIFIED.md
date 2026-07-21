# VERIFIED.md — resolved API claims and gate results

Append-only. Every SPEC.md Appendix B claim resolved here with evidence
(fetched URL or command output) before dependent code is written.
Gate results recorded per the format in BUILD-PHASES.md.

## 2026-07-21 — Task 0.1 Source-of-truth capture

**Sources (authoritative, fetched — not training memory):**
- `guacamole-ext` 1.6.0 sources jar — `https://repo1.maven.org/maven2/org/apache/guacamole/guacamole-ext/1.6.0/guacamole-ext-1.6.0-sources.jar`
- `guacamole-common` 1.6.0 sources jar — `https://repo1.maven.org/maven2/org/apache/guacamole/guacamole-common/1.6.0/guacamole-common-1.6.0-sources.jar`
- `ExtensionManifest.java` @ tag 1.6.0 — `https://raw.githubusercontent.com/apache/guacamole-client/1.6.0/guacamole/src/main/java/org/apache/guacamole/extension/ExtensionManifest.java`
- root `pom.xml` @ tag 1.6.0 — `https://raw.githubusercontent.com/apache/guacamole-client/1.6.0/pom.xml`

### Appendix B #2 — event type inventory (Gate 0.2)

`Listener` interface: `org.apache.guacamole.net.event.listener.Listener`, single
method `void handleEvent(Object event) throws GuacamoleException`. Matches SPEC
§6.2 exactly. Listeners notified in manifest order; throwing vetoes the action.

Full 1.6.0 event class set (`org.apache.guacamole.net.event`), richer than the
4 SPEC assumed:
- `AuthenticationSuccessEvent`, `AuthenticationFailureEvent`
- `TunnelConnectEvent`, `TunnelCloseEvent`
- `AuthenticationRequestReceivedEvent`, `AuthenticationProviderEvent`
- `CredentialEvent`, `FailureEvent`, `UserEvent`, `TunnelEvent` (marker/base ifaces)
- `DirectoryEvent`, `DirectorySuccessEvent`, `DirectoryFailureEvent`, `IdentifiableObjectEvent`
- `ApplicationStartedEvent`, `ApplicationShutdownEvent`, `UserSessionInvalidatedEvent`

v1 consumes only the connect/close + auth pair. The rest are upside, out of scope for Phase 1.

Accessor signatures (from source):
- `TunnelConnectEvent implements UserEvent, CredentialEvent, TunnelEvent`
  → `getAuthenticatedUser()`, `getCredentials()`, `getTunnel()`. **No `getUserContext()`** despite the javadoc prose.
- `AuthenticationSuccessEvent` → `getAuthenticatedUser()`, `getCredentials()` (= `authenticatedUser.getCredentials()`), `getAuthenticationProvider()`, **`isExistingSession()`**.
- `AuthenticationFailureEvent implements AuthenticationProviderEvent, CredentialEvent, FailureEvent`
  → `getCredentials()`, `getAuthenticationProvider()` (nullable), **`getFailure()` → `Throwable`** (nullable).

**Schema-shaping noise findings (feed Phase 1):**
- `AuthenticationSuccessEvent.isExistingSession()` is `true` for periodic token
  re-auth (every few min per active session), `false` for a fresh login. A raw
  `auth.success` per event is noisy — Phase 1 must tag `guacamole.auth.existing_session`
  and/or treat only `false` as a login.
- `Credentials.isEmpty()` is `true` for the initial anonymous credential-less hit
  that renders the login screen; it fires an `AuthenticationFailureEvent`. Phase 1
  must skip empty-credential failures or they dominate `auth.failure`.

### Appendix B #3 — does TunnelCloseEvent carry a close reason? — **NO**

`TunnelCloseEvent` fields are exactly `authenticatedUser`, `credentials`,
`tunnel` — accessors `getAuthenticatedUser/getCredentials/getTunnel` only. No
status, no reason, no Throwable. **Consequence:** SPEC §7.1 span status can only
be `OK` on clean close; there is no close-carried failure to map to `ERROR`.
`guacamole.session.end_reason` ∈ {`closed`, `timeout`} only — drop `error` as a
close outcome. Update SPEC §7.1 accordingly in Phase 1.

### Appendix B #4 — connect → connection id + protocol traversal (Gate 0.3, static portion)

`GuacamoleTunnel` (guacamole-common) exposes only `getUUID()` (per-tunnel UUID),
`getSocket()`, reader/writer, `close()`, `isOpen()`. **No connection id, no
protocol, no config on the tunnel itself.** Traversal is through the socket:

- **protocol** — `GuacamoleSocket.getProtocol()` is a `default` interface method;
  `ConfiguredGuacamoleSocket` overrides it to return `config.getProtocol()`, and
  `DelegatingGuacamoleSocket.getProtocol()` delegates down the wrap chain. So
  `tunnel.getSocket().getProtocol()` yields the protocol (`rdp`/`vnc`/`ssh`/…)
  **through any delegation layer, zero cost, no directory lookup.** → SPEC §13.4
  TTL cache is NOT needed for protocol (pending runtime confirmation the value is
  populated at connect time). On null, emit `protocol=unknown` (L5).
- **connection id (guacd correlation key)** — `ConfiguredGuacamoleSocket.getConnectionID()`
  returns the id from guacd's `ready` instruction — the same id guacd logs
  reference (Gate 0.4). BUT this method is on the **concrete** class only, not on
  the `GuacamoleSocket` interface, and `getDelegateSocket()` is `protected`. If
  the runtime socket is wrapped (Filtered/Monitoring/Delegating), an
  `instanceof ConfiguredGuacamoleSocket` check fails and the id is unreachable
  without reflection. **This is the runtime unknown Gate 0.2/0.3 probe must
  resolve: what concrete socket type is `getSocket()` at `TunnelConnectEvent` time?**
- Distinguish two "connection ids": `ConfiguredGuacamoleSocket.getConnectionID()`
  (guacd `ready` id, the correlation key) vs `GuacamoleConfiguration.getConnectionID()`
  (id of a connection being *joined*, usually null). SPEC's `guacamole.connection.id`
  = the former.
- **connection *name*** (`prod-jump-01`, SPEC §7.1) lives on the connection object
  in the directory; reaching it needs a `UserContext` → `getConnectionDirectory()`
  lookup, and `TunnelConnectEvent` gives no `UserContext`. Likely dropped or
  deferred in Phase 1 unless the probe finds another path. Flag for Gate 0.3.

### Credentials accessors + G3

`Credentials` (guacamole-ext): SAFE fields for telemetry — `getUsername()`
(→ `enduser.id`; or prefer `AuthenticatedUser.getIdentifier()`, `AuthenticatedUser
extends Identifiable`, `ANONYMOUS_IDENTIFIER=""`), `getRemoteAddress()`
(→ `client.address`), `getRemoteHostname()`. **`getPassword()` exists and MUST
never be called (G3).** Also avoid the generic `getHeader/getParameter/getRequestDetails/
getRequest(deprecated)` paths — allowlist only.

### Appendix B #8 — guac-manifest.json schema — RESOLVED

`ExtensionManifest` JSON keys (field name = JSON key unless `@JsonProperty`):
`guacamoleVersion` (String), `name` (String), `namespace` (String),
`js`/`css`/`html`/`translations`/`resources`, `authProviders` (Collection<String>),
**`listeners` (Collection<String>** — FQCNs of `Listener` impls), `smallIcon`,
`largeIcon`. Confirms SPEC §6.1 manifest verbatim. Open: the `guacamoleVersion`
value is a plain String; the `"1.6.*"` wildcard match semantics are enforced by
the loader, not this class — confirm the running app accepts `1.6.*` during the probe.

### Appendix B #7 — §16 filter mechanism reachability (docs only, stays UNBUILT)

Confirmed reachable from an extension classpath: subclass `DelegatingUserContext`
(exposes `getConnectionDirectory() → Directory<Connection>`), wrap the tunnel's
socket in `FilteredGuacamoleSocket(GuacamoleSocket, GuacamoleFilter readFilter,
GuacamoleFilter writeFilter)`, `GuacamoleFilter.filter(GuacamoleInstruction)`.
Mechanism is stock; per governing principle it is **not built**. Recorded for
`docs/EXTENSION-POINTS.md` only.

### Java baseline — CONFLICT with CLAUDE.md/SPEC (needs human decision)

guacamole-client 1.6.0 root `pom.xml` compiles at `<source>1.8</source>
<target>1.8</target>` → **Java 8 bytecode**. CLAUDE.md "Environment facts" and
SPEC say "Java 17 (verify, G8)". **Verified: baseline is Java 8, not 17.** The
extension is loaded by whatever JVM runs Tomcat; targeting Java 8 bytecode
(`maven.compiler.release=8`, build with any JDK ≥8) maximises load-compat. OTel
Java SDK supports Java 8. Recommendation: build extension with `release=8` (or 11
if the deployment JVM is pinned newer). **Do not silently change CLAUDE.md (G10);
raise at the Phase 0 human gate.**
`guacamole-ext` provides (transitively, `provided` scope): guava, jackson-databind,
ipaddress, servlet-api — available at runtime, need not be bundled.

### Still open — require the running probe (Gates 0.1, 0.3-runtime, 0.4)

- **Gate 0.1** (blocking): `GlobalOpenTelemetry.get().getClass().getName()` from the
  extension classloader under the real OTel Java agent → SDK class (agent-bridge)
  vs no-op (bundled-SDK). Static analysis cannot answer this.
- **Gate 0.3-runtime:** concrete type of `tunnel.getSocket()` at connect time —
  determines whether `getConnectionID()` is reachable and whether protocol is
  populated then.
- **Gate 0.4:** guacd log line format + whether the `ready`/connection id appears
  at info level; yields the collector `transform` regex.

_Gate P0 exit NOT marked passed — pending probe + human review (G10)._
