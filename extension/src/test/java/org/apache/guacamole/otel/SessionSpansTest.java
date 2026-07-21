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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Span lifecycle: timestamps and duration come from event time; timeout semantics. */
class SessionSpansTest {

    private InMemoryMetricReader metrics;
    private InMemorySpanExporter spans;
    private SessionSpans sessionSpans;

    @BeforeEach
    void setUp() {
        metrics = InMemoryMetricReader.create();
        spans = InMemorySpanExporter.create();
        InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
        OpenTelemetry otel = TestSupport.sdk(metrics, spans, logs);
        Instruments instruments = new Instruments(otel.getMeter(Instruments.SCOPE));
        sessionSpans = new SessionSpans(
                otel.getTracer(Instruments.SCOPE),
                otel.getLogsBridge().get(Instruments.SCOPE),
                instruments,
                Config.defaults());
    }

    @Test
    void durationAndTimestampsComeFromEventTime() {
        long start = 1_700_000_000_000L;
        long end = start + 5_000L; // 5 seconds

        SessionRegistry.SessionState state = sessionSpans.onConnect(
                "uuid-1", "$cid-1", "ssh", "postgresql", "alice", "10.0.0.1", start);
        sessionSpans.onClose(state, "uuid-1", "$cid-1", "alice", "10.0.0.1", end);

        SpanData s = spans.getFinishedSpanItems().get(0);
        assertEquals(start, TimeUnit.NANOSECONDS.toMillis(s.getStartEpochNanos()));
        assertEquals(end, TimeUnit.NANOSECONDS.toMillis(s.getEndEpochNanos()));
        assertEquals(Attributes.END_REASON_CLOSED, s.getAttributes().get(Attributes.END_REASON));

        // Duration histogram records exactly 5 seconds.
        assertEquals(5.0, TestSupport.histogramSum(metrics, "guacamole.session.duration"), 0.0001);
    }

    @Test
    void timeoutEndsOrphanWithTimeoutReasonAndUnsetStatus() {
        long start = 1_700_000_000_000L;

        SessionRegistry.SessionState state = sessionSpans.onConnect(
                "uuid-2", "$cid-2", "rdp", "postgresql", "bob", "10.0.0.2", start);
        sessionSpans.onTimeout(state, start + 86_400_000L); // one day later

        SpanData s = spans.getFinishedSpanItems().get(0);
        assertEquals(Attributes.END_REASON_TIMEOUT, s.getAttributes().get(Attributes.END_REASON));
        assertEquals(io.opentelemetry.api.trace.StatusCode.UNSET, s.getStatus().getStatusCode());
        assertEquals(86400.0, TestSupport.histogramSum(metrics, "guacamole.session.duration"), 0.0001);
    }
}
