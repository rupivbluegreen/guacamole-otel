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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every emitted log record must carry severity TEXT, not just the severity number.
 *
 * <p>OTLP makes {@code severity_text} optional, but some backends reject records that
 * omit it — and they do so SILENTLY: the collector still counts the record as sent,
 * the transport still acks the batch, and no error surfaces in the collector, the
 * extension, or the backend. Auth and session evidence records then simply do not
 * exist downstream, with nothing anywhere to explain it. Unlike a missing
 * {@code service.name} (which is defaulted to {@code unknown_service}), a missing
 * severity text is not filled in for us, so the two cannot be reasoned about alike.
 *
 * <p>This test guards both emitters: {@link AuthEvents} and {@link SessionSpans}.
 */
class SeverityTextTest {

    private InMemoryLogRecordExporter logs;
    private AuthEvents authEvents;
    private SessionSpans sessionSpans;

    @BeforeEach
    void setUp() {
        InMemoryMetricReader metrics = InMemoryMetricReader.create();
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        logs = InMemoryLogRecordExporter.create();
        OpenTelemetry otel = TestSupport.sdk(metrics, spans, logs);
        Instruments instruments = new Instruments(otel.getMeter(Instruments.SCOPE));
        authEvents = new AuthEvents(
                otel.getLogsBridge().get(Instruments.SCOPE), instruments, Config.defaults());
        sessionSpans = new SessionSpans(
                otel.getTracer(Instruments.SCOPE),
                otel.getLogsBridge().get(Instruments.SCOPE),
                instruments,
                Config.defaults());
    }

    @Test
    void authSuccessRecordCarriesSeverityText() {
        authEvents.onSuccess("alice", "10.0.0.1", "postgresql", 1_700_000_000_000L);

        List<LogRecordData> records = logs.getFinishedLogRecordItems();
        assertEquals(1, records.size());
        assertEquals(AuthEvents.EVENT_SUCCESS,
                records.get(0).getAttributes().get(Attributes.EVENT_NAME));
        assertSeverityTextMatchesSeverity(records);
    }

    @Test
    void authFailureRecordCarriesSeverityText() {
        authEvents.onFailure("bob", "10.0.0.5", "postgresql",
                "GuacamoleInvalidCredentialsException", 1_700_000_000_000L);

        List<LogRecordData> records = logs.getFinishedLogRecordItems();
        assertEquals(1, records.size());
        assertEquals(AuthEvents.EVENT_FAILURE,
                records.get(0).getAttributes().get(Attributes.EVENT_NAME));
        assertSeverityTextMatchesSeverity(records);
    }

    @Test
    void sessionConnectAndCloseRecordsCarrySeverityText() {
        long start = 1_700_000_000_000L;

        SessionRegistry.SessionState state = sessionSpans.onConnect(
                "uuid-1", "$cid-1", "ssh", "postgresql", "alice", "10.0.0.1", start);
        sessionSpans.onClose(state, "uuid-1", "$cid-1", "alice", "10.0.0.1", start + 5_000L);

        List<LogRecordData> records = logs.getFinishedLogRecordItems();
        assertEquals(2, records.size(), "connect + close each emit one record");
        assertEquals(SessionSpans.EVENT_CONNECTED,
                records.get(0).getAttributes().get(Attributes.EVENT_NAME));
        assertEquals(SessionSpans.EVENT_CLOSED,
                records.get(1).getAttributes().get(Attributes.EVENT_NAME));
        assertSeverityTextMatchesSeverity(records);
    }

    /**
     * Every record must set severity text to the severity's own name — non-null,
     * non-empty, and consistent with the numeric severity.
     */
    private static void assertSeverityTextMatchesSeverity(List<LogRecordData> records) {
        for (LogRecordData record : records) {
            String eventName = record.getAttributes().get(Attributes.EVENT_NAME);
            String severityText = record.getSeverityText();
            assertNotNull(severityText,
                    "severity_text is null on " + eventName
                            + " — backends that require it drop the record silently");
            assertFalse(severityText.isEmpty(),
                    "severity_text is empty on " + eventName);
            assertEquals(record.getSeverity().name(), severityText,
                    "severity_text must match the numeric severity on " + eventName);
        }
    }
}
