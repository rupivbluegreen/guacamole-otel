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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.UUID;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.AuthenticationProvider;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.event.AuthenticationFailureEvent;
import org.apache.guacamole.net.event.AuthenticationSuccessEvent;
import org.apache.guacamole.net.event.TunnelCloseEvent;
import org.apache.guacamole.net.event.TunnelConnectEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end listener behaviour, including the G1 and G3 acceptance tests. */
class OtelListenerTest {

    private static final String PASSWORD = "S3cr3t-P@ssw0rd";

    private InMemoryMetricReader metrics;
    private InMemorySpanExporter spans;
    private InMemoryLogRecordExporter logs;
    private OtelListener listener;

    @BeforeEach
    void setUp() {
        metrics = InMemoryMetricReader.create();
        spans = InMemorySpanExporter.create();
        logs = InMemoryLogRecordExporter.create();
        OpenTelemetry otel = TestSupport.sdk(metrics, spans, logs);
        listener = new OtelListener(Config.defaults(), otel);
        assertTrue(listener.isEnabled(), "listener should be enabled with an SDK");
    }

    @Test
    void connectThenClose_producesExactlyOneCompleteSpan() {
        UUID uuid = UUID.randomUUID();
        GuacamoleTunnel tunnel = TestSupport.tunnel(uuid, TestSupport.socket("ssh"));
        AuthenticationProvider ap = TestSupport.authProvider("postgresql");
        Credentials creds = TestSupport.credentials("alice", PASSWORD, "10.4.2.9");
        AuthenticatedUser user = TestSupport.authUser("alice", ap, creds);

        listener.handleEvent(new TunnelConnectEvent(user, creds, tunnel));
        listener.handleEvent(new TunnelCloseEvent(user, creds, tunnel));

        List<SpanData> exported = spans.getFinishedSpanItems();
        assertEquals(1, exported.size(), "exactly one span");
        SpanData s = exported.get(0);
        assertEquals(SessionSpans.SPAN_NAME, s.getName());
        assertEquals(io.opentelemetry.api.trace.SpanKind.SERVER, s.getKind());
        assertEquals("ssh", s.getAttributes().get(Attributes.PROTOCOL));
        assertEquals("postgresql", s.getAttributes().get(Attributes.DATASOURCE));
        assertEquals("alice", s.getAttributes().get(Attributes.ENDUSER_ID));
        assertEquals("10.4.2.9", s.getAttributes().get(Attributes.CLIENT_ADDRESS));
        assertEquals(uuid.toString(), s.getAttributes().get(Attributes.TUNNEL_UUID));
        assertEquals(Attributes.END_REASON_CLOSED, s.getAttributes().get(Attributes.END_REASON));
        assertEquals(io.opentelemetry.api.trace.StatusCode.OK, s.getStatus().getStatusCode());
        assertTrue(s.getEndEpochNanos() >= s.getStartEpochNanos());

        // G3: the password never appears anywhere on the span.
        s.getAttributes().forEach((k, v) ->
                assertNotEquals(PASSWORD, String.valueOf(v), "password leaked into span attr " + k.getKey()));

        // Registry drained; active back to zero; one start counted.
        assertEquals(0, listener.registry().size());
        assertEquals(0, TestSupport.longSumTotal(metrics, "guacamole.session.active"));
        assertEquals(1, TestSupport.longSumTotal(metrics, "guacamole.session.started"));
    }

    @Test
    void closeWithoutConnect_isNoOpButCounted() {
        UUID uuid = UUID.randomUUID();
        GuacamoleTunnel tunnel = TestSupport.tunnel(uuid, TestSupport.socket("rdp"));

        listener.handleEvent(new TunnelCloseEvent(null, null, tunnel));

        assertEquals(0, spans.getFinishedSpanItems().size(), "no span for an unmatched close");
        assertTrue(TestSupport.longSum(metrics, "guacamole.otel.errors",
                "guacamole.otel.stage", "unmatched_close") >= 1, "unmatched close counted");
    }

    @Test
    void authFailure_neverEmitsThePassword() {
        Credentials creds = TestSupport.credentials("bob", PASSWORD, "10.0.0.5");

        listener.handleEvent(new AuthenticationFailureEvent(creds,
                new RuntimeException("nope")));

        List<LogRecordData> records = logs.getFinishedLogRecordItems();
        assertEquals(1, records.size());
        LogRecordData r = records.get(0);
        assertEquals("guacamole.auth.failure", r.getAttributes().get(Attributes.EVENT_NAME));
        assertEquals("failure", r.getAttributes().get(Attributes.AUTH_OUTCOME));
        assertEquals("bob", r.getAttributes().get(Attributes.ENDUSER_ID));
        // G3: no attribute value equals the password (the body is a fixed string).
        r.getAttributes().forEach((k, v) ->
                assertNotEquals(PASSWORD, String.valueOf(v), "password leaked into log attr " + k.getKey()));
    }

    @Test
    void throwingExporter_handleEventStillReturnsNormally() {
        OpenTelemetry throwingOtel = TestSupport.sdk(
                InMemoryMetricReader.create(),
                TestSupport.throwingSpanExporter(),
                InMemoryLogRecordExporter.create());
        OtelListener l = new OtelListener(Config.defaults(), throwingOtel);
        UUID uuid = UUID.randomUUID();
        GuacamoleTunnel tunnel = TestSupport.tunnel(uuid, TestSupport.socket("ssh"));

        assertDoesNotThrow(() -> {
            l.handleEvent(new TunnelConnectEvent(null, null, tunnel));
            l.handleEvent(new TunnelCloseEvent(null, null, tunnel));
        }, "a throwing exporter must never propagate out of handleEvent (G1)");
    }

    @Test
    void reAuthenticationSuccess_isSkipped() {
        Credentials creds = TestSupport.credentials("alice", PASSWORD, "10.0.0.1");
        AuthenticatedUser user = TestSupport.authUser("alice",
                TestSupport.authProvider("postgresql"), creds);

        listener.handleEvent(new AuthenticationSuccessEvent(user, true));

        assertEquals(0, logs.getFinishedLogRecordItems().size(), "re-auth must not emit a record");
        assertEquals(0, TestSupport.longSumTotal(metrics, "guacamole.auth.attempts"));
    }

    @Test
    void freshLoginSuccess_emitsRecordAndCounts() {
        Credentials creds = TestSupport.credentials("alice", PASSWORD, "10.0.0.1");
        AuthenticatedUser user = TestSupport.authUser("alice",
                TestSupport.authProvider("postgresql"), creds);

        listener.handleEvent(new AuthenticationSuccessEvent(user, false));

        List<LogRecordData> records = logs.getFinishedLogRecordItems();
        assertEquals(1, records.size());
        assertEquals("guacamole.auth.success", records.get(0).getAttributes().get(Attributes.EVENT_NAME));
        assertEquals("alice", records.get(0).getAttributes().get(Attributes.ENDUSER_ID));
        assertEquals(1, TestSupport.longSum(metrics, "guacamole.auth.attempts",
                "guacamole.auth.outcome", "success"));
    }

    @Test
    void emptyCredentialFailure_isSkipped() {
        Credentials empty = TestSupport.credentials(null, null, null);
        assertTrue(empty.isEmpty(), "precondition: credentials are empty");

        listener.handleEvent(new AuthenticationFailureEvent(empty));

        assertEquals(0, logs.getFinishedLogRecordItems().size(),
                "empty anonymous failures must be filtered");
    }
}
