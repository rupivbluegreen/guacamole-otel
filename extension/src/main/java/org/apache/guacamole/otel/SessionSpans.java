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

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.concurrent.TimeUnit;

/**
 * Session span lifecycle (SPEC §7) plus the real-time session log records (§9).
 *
 * <p>A span is exported only when it ends, so a long session's span reaches the
 * backend hours late (§7.2). The real-time signal is the {@code session.active}
 * metric and the {@code guacamole.session.connected} log record emitted here at
 * connect time — never span counts.
 *
 * <p>Timestamps come from event time (SPEC): the span starts at the connect event's
 * instant and ends at the close event's instant (or the sweep instant on timeout).
 */
public final class SessionSpans {

    static final String SPAN_NAME = "guacamole.session";
    static final String EVENT_CONNECTED = "guacamole.session.connected";
    static final String EVENT_CLOSED = "guacamole.session.closed";

    private final Tracer tracer;
    private final Logger logs;
    private final Instruments instruments;
    private final Config config;

    public SessionSpans(Tracer tracer, Logger logs, Instruments instruments, Config config) {
        this.tracer = tracer;
        this.logs = logs;
        this.instruments = instruments;
        this.config = config;
    }

    /**
     * Starts a session span, bumps started/active counters, and emits the
     * connected log record. Returns the state to register against the tunnel UUID.
     *
     * @param connectionId guacd connection id (correlation key), may be null
     * @param protocol     resolved protocol, or "unknown"
     * @param enduserId    raw user id (pseudonymised here per config)
     * @param clientAddress remote address, may be null
     * @param eventEpochMillis connect event time
     */
    public SessionRegistry.SessionState onConnect(String tunnelUuid, String connectionId,
            String protocol, String datasource, String enduserId, String clientAddress,
            long eventEpochMillis) {

        String user = config.pseudonymizeUser(enduserId);

        AttributesBuilder attrs = io.opentelemetry.api.common.Attributes.builder();
        putIfPresent(attrs, Attributes.TUNNEL_UUID, tunnelUuid);
        putIfPresent(attrs, Attributes.CONNECTION_ID, connectionId);
        putIfPresent(attrs, Attributes.PROTOCOL, protocol);
        putIfPresent(attrs, Attributes.DATASOURCE, datasource);
        putIfPresent(attrs, Attributes.ENDUSER_ID, user);
        putIfPresent(attrs, Attributes.CLIENT_ADDRESS, clientAddress);
        io.opentelemetry.api.common.Attributes spanAttrs = attrs.build();

        Span span = tracer.spanBuilder(SPAN_NAME)
                .setSpanKind(SpanKind.SERVER)
                .setStartTimestamp(eventEpochMillis, TimeUnit.MILLISECONDS)
                .setAllAttributes(spanAttrs)
                .startSpan();

        io.opentelemetry.api.common.Attributes dims = sessionDims(protocol, datasource);
        instruments.sessionStarted().add(1, dims);
        instruments.sessionActive().add(1, dims);

        emitLog(EVENT_CONNECTED, Severity.INFO, spanAttrs, eventEpochMillis,
                "Guacamole session connected");

        return new SessionRegistry.SessionState(span, eventEpochMillis, protocol, datasource);
    }

    /** Normal close: ends the span OK, records duration, emits the closed log record. */
    public void onClose(SessionRegistry.SessionState state, String tunnelUuid,
            String connectionId, String enduserId, String clientAddress, long eventEpochMillis) {

        endSpan(state, Attributes.END_REASON_CLOSED, StatusCode.OK, eventEpochMillis);

        String user = config.pseudonymizeUser(enduserId);
        AttributesBuilder attrs = io.opentelemetry.api.common.Attributes.builder();
        putIfPresent(attrs, Attributes.TUNNEL_UUID, tunnelUuid);
        putIfPresent(attrs, Attributes.CONNECTION_ID, connectionId);
        putIfPresent(attrs, Attributes.PROTOCOL, state.protocol());
        putIfPresent(attrs, Attributes.DATASOURCE, state.datasource());
        putIfPresent(attrs, Attributes.ENDUSER_ID, user);
        putIfPresent(attrs, Attributes.CLIENT_ADDRESS, clientAddress);
        attrs.put(Attributes.END_REASON, Attributes.END_REASON_CLOSED);
        emitLog(EVENT_CLOSED, Severity.INFO, attrs.build(), eventEpochMillis,
                "Guacamole session closed");
    }

    /**
     * TTL/capacity eviction: ends an orphaned span with {@code end_reason=timeout},
     * status left UNSET (no clean close observed). Called from the registry sweep.
     */
    public void onTimeout(SessionRegistry.SessionState state, long endEpochMillis) {
        endSpan(state, Attributes.END_REASON_TIMEOUT, null, endEpochMillis);
    }

    private void endSpan(SessionRegistry.SessionState state, String endReason,
            StatusCode status, long endEpochMillis) {

        Span span = state.span();
        span.setAttribute(Attributes.END_REASON, endReason);
        if (status != null) {
            span.setStatus(status);
        }
        span.end(endEpochMillis, TimeUnit.MILLISECONDS);

        io.opentelemetry.api.common.Attributes dims =
                sessionDimsWithReason(state.protocol(), state.datasource(), endReason);
        instruments.sessionActive().add(-1, sessionDims(state.protocol(), state.datasource()));
        double durationSeconds = Math.max(0, (endEpochMillis - state.startEpochMillis()) / 1000.0);
        instruments.sessionDuration().record(durationSeconds, dims);
    }

    private static io.opentelemetry.api.common.Attributes sessionDims(String protocol, String datasource) {
        return Attributes.MetricDimensions.create()
                .protocol(protocol)
                .datasource(datasource)
                .build();
    }

    private static io.opentelemetry.api.common.Attributes sessionDimsWithReason(
            String protocol, String datasource, String endReason) {
        return Attributes.MetricDimensions.create()
                .protocol(protocol)
                .datasource(datasource)
                .endReason(endReason)
                .build();
    }

    private void emitLog(String eventName, Severity severity,
            io.opentelemetry.api.common.Attributes attrs, long epochMillis, String body) {
        logs.logRecordBuilder()
                .setTimestamp(epochMillis, TimeUnit.MILLISECONDS)
                .setSeverity(severity)
                .setAllAttributes(attrs)
                .setAttribute(Attributes.EVENT_NAME, eventName)
                .setBody(body)
                .emit();
    }

    private static void putIfPresent(AttributesBuilder b,
            io.opentelemetry.api.common.AttributeKey<String> key, String value) {
        if (value != null) {
            b.put(key, value);
        }
    }
}
