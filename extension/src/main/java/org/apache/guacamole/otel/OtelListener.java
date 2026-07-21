/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.guacamole.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import org.apache.guacamole.net.GuacamoleSocket;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.event.AuthenticationFailureEvent;
import org.apache.guacamole.net.event.AuthenticationSuccessEvent;
import org.apache.guacamole.net.event.TunnelCloseEvent;
import org.apache.guacamole.net.event.TunnelConnectEvent;
import org.apache.guacamole.net.event.listener.Listener;
import org.apache.guacamole.protocol.ConfiguredGuacamoleSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The extension entry point: dispatches Guacamole events to telemetry, in total
 * isolation from Guacamole availability.
 *
 * <p><b>Guardrail G1 — {@code handleEvent} never propagates.</b> Throwing from a
 * listener vetoes the action in progress (an exception on auth-success denies the
 * login; on tunnel-connect denies the connection). Every dispatch is wrapped in
 * {@code catch (Throwable)} — {@code Throwable}, not {@code Exception}, because a
 * classloader mismatch surfaces as {@code NoClassDefFoundError}. A telemetry defect
 * must never become an availability incident.
 *
 * <p><b>Guardrail G2 — observe only.</b> This listener never throws to veto, never
 * blocks, never denies. It records and returns.
 */
public class OtelListener implements Listener {

    private static final Logger logger = LoggerFactory.getLogger(OtelListener.class);

    private static final String STAGE_DISPATCH = "handleEvent";
    private static final String STAGE_UNMATCHED_CLOSE = "unmatched_close";
    private static final String PROTOCOL_UNKNOWN = "unknown";

    private final Config config;
    private final boolean enabled;
    private final Instruments instruments;
    private final SessionRegistry registry;
    private final SessionSpans sessionSpans;
    private final AuthEvents authEvents;

    /** Invoked by Guacamole via the manifest. Fails open if OpenTelemetry is unavailable. */
    public OtelListener() {
        this(Config.load(), resolveGlobalOpenTelemetry());
    }

    /** Wiring constructor (also the test seam — pass an SDK-backed OpenTelemetry). */
    OtelListener(Config config, OpenTelemetry openTelemetry) {
        this.config = config;

        Instruments instr = null;
        SessionRegistry reg = null;
        SessionSpans spans = null;
        AuthEvents auth = null;
        boolean ok = false;

        if (config.isEnabled() && openTelemetry != null) {
            try {
                Tracer tracer = openTelemetry.getTracer(Instruments.SCOPE);
                Meter meter = openTelemetry.getMeter(Instruments.SCOPE);
                io.opentelemetry.api.logs.Logger logs =
                        openTelemetry.getLogsBridge().get(Instruments.SCOPE);
                instr = new Instruments(meter);
                spans = new SessionSpans(tracer, logs, instr, config);
                auth = new AuthEvents(logs, instr, config);
                reg = new SessionRegistry(config, instr, this::onEvicted);
                ok = true;
            }
            catch (Throwable t) {
                // §13.6 fail open: never prevent extension load.
                logger.warn("OpenTelemetry unavailable; Guacamole OTel extension disabled.", t);
            }
        }

        this.instruments = instr;
        this.registry = reg;
        this.sessionSpans = spans;
        this.authEvents = auth;
        this.enabled = ok;
    }

    // ---- Guardrail G1: the isolation envelope. Any change here needs human sign-off. ----
    @Override
    public void handleEvent(Object event) {
        if (!enabled) {
            return;
        }
        try {
            if (event instanceof TunnelConnectEvent) {
                onConnect((TunnelConnectEvent) event);
            }
            else if (event instanceof TunnelCloseEvent) {
                onClose((TunnelCloseEvent) event);
            }
            else if (event instanceof AuthenticationSuccessEvent) {
                onAuthSuccess((AuthenticationSuccessEvent) event);
            }
            else if (event instanceof AuthenticationFailureEvent) {
                onAuthFailure((AuthenticationFailureEvent) event);
            }
        }
        catch (Throwable t) {
            // A telemetry defect must never veto a Guacamole action. Swallow everything
            // (including NoClassDefFoundError), count it, and return normally.
            countSuppressed(STAGE_DISPATCH, t);
        }
    }
    // ---- end G1 envelope ----

    private void onConnect(TunnelConnectEvent event) {
        GuacamoleTunnel tunnel = event.getTunnel();
        if (tunnel == null || tunnel.getUUID() == null) {
            return;
        }

        GuacamoleSocket socket = tunnel.getSocket();
        String protocol = (socket != null) ? socket.getProtocol() : null;
        if (protocol == null) {
            protocol = PROTOCOL_UNKNOWN;
        }
        String connectionId = (socket instanceof ConfiguredGuacamoleSocket)
                ? ((ConfiguredGuacamoleSocket) socket).getConnectionID()
                : null;

        AuthenticatedUser user = event.getAuthenticatedUser();
        String enduserId = (user != null) ? user.getIdentifier() : null;
        String datasource = datasourceOf(user);
        Credentials credentials = event.getCredentials();
        String clientAddress = (credentials != null) ? credentials.getRemoteAddress() : null;

        SessionRegistry.SessionState state = sessionSpans.onConnect(
                tunnel.getUUID().toString(), connectionId, protocol, datasource,
                enduserId, clientAddress, System.currentTimeMillis());
        registry.put(tunnel.getUUID(), state);
    }

    private void onClose(TunnelCloseEvent event) {
        GuacamoleTunnel tunnel = event.getTunnel();
        if (tunnel == null || tunnel.getUUID() == null) {
            return;
        }

        SessionRegistry.SessionState state = registry.remove(tunnel.getUUID());
        if (state == null) {
            // Unmatched close — expected after a webapp restart (restart-lossy, G6).
            // No-op beyond a benign self-telemetry count.
            instruments.otelErrors().add(1,
                    Attributes.MetricDimensions.create().stage(STAGE_UNMATCHED_CLOSE).build());
            return;
        }

        GuacamoleSocket socket = tunnel.getSocket();
        String connectionId = (socket instanceof ConfiguredGuacamoleSocket)
                ? ((ConfiguredGuacamoleSocket) socket).getConnectionID()
                : null;
        Credentials credentials = event.getCredentials();
        String enduserId = (event.getAuthenticatedUser() != null)
                ? event.getAuthenticatedUser().getIdentifier() : null;
        String clientAddress = (credentials != null) ? credentials.getRemoteAddress() : null;

        sessionSpans.onClose(state, tunnel.getUUID().toString(), connectionId,
                enduserId, clientAddress, System.currentTimeMillis());
    }

    private void onAuthSuccess(AuthenticationSuccessEvent event) {
        // Skip periodic re-authentication of an established session (§9 noise filter).
        if (event.isExistingSession()) {
            return;
        }
        AuthenticatedUser user = event.getAuthenticatedUser();
        String enduserId = (user != null) ? user.getIdentifier() : null;
        String datasource = datasourceOf(user);
        Credentials credentials = event.getCredentials();
        String clientAddress = (credentials != null) ? credentials.getRemoteAddress() : null;

        authEvents.onSuccess(enduserId, clientAddress, datasource, System.currentTimeMillis());
    }

    private void onAuthFailure(AuthenticationFailureEvent event) {
        Credentials credentials = event.getCredentials();
        // Skip the initial credential-less anonymous hit that renders the login screen (§9).
        if (credentials != null && credentials.isEmpty()) {
            return;
        }
        String enduserId = (credentials != null) ? credentials.getUsername() : null;
        String clientAddress = (credentials != null) ? credentials.getRemoteAddress() : null;
        String datasource = (event.getAuthenticationProvider() != null)
                ? event.getAuthenticationProvider().getIdentifier() : null;
        String failureType = (event.getFailure() != null)
                ? event.getFailure().getClass().getSimpleName() : null;

        authEvents.onFailure(enduserId, clientAddress, datasource, failureType,
                System.currentTimeMillis());
    }

    /** Registry callback: an orphaned session evicted by TTL or capacity. */
    private void onEvicted(SessionRegistry.SessionState state, String reason) {
        sessionSpans.onTimeout(state, System.currentTimeMillis());
    }

    private static String datasourceOf(AuthenticatedUser user) {
        if (user == null || user.getAuthenticationProvider() == null) {
            return null;
        }
        return user.getAuthenticationProvider().getIdentifier();
    }

    /** Self-telemetry that itself cannot throw out of the caller (protects G1). */
    private void countSuppressed(String stage, Throwable cause) {
        try {
            instruments.otelErrors().add(1,
                    Attributes.MetricDimensions.create().stage(stage).build());
            logger.debug("otel listener suppressed error at stage {}", stage, cause);
        }
        catch (Throwable ignored) {
            // Absolutely never propagate from the isolation envelope.
        }
    }

    private static OpenTelemetry resolveGlobalOpenTelemetry() {
        try {
            return GlobalOpenTelemetry.get();
        }
        catch (Throwable t) {
            return null;
        }
    }

    // Test accessors.
    boolean isEnabled() { return enabled; }
    SessionRegistry registry() { return registry; }
}
