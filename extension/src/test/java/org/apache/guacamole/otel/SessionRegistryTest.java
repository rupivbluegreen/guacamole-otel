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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Guardrail G6: the registry stays bounded (capacity + TTL sweep). */
class SessionRegistryTest {

    private InMemoryMetricReader metrics;
    private Instruments instruments;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        metrics = InMemoryMetricReader.create();
        OpenTelemetry otel = TestSupport.sdk(metrics,
                InMemorySpanExporter.create(), InMemoryLogRecordExporter.create());
        instruments = new Instruments(otel.getMeter(Instruments.SCOPE));
        tracer = otel.getTracer(Instruments.SCOPE);
    }

    private SessionRegistry.SessionState state(long startMillis) {
        Span span = tracer.spanBuilder("x").startSpan();
        return new SessionRegistry.SessionState(span, startMillis, "ssh", "postgresql");
    }

    @Test
    void atCapacityEvictsOldestAndDoesNotGrow() {
        Config cfg = new Config(true, 2, hours(24), minutes(60), false, false, null);
        List<String> reasons = new ArrayList<>();
        SessionRegistry registry = new SessionRegistry(cfg, instruments,
                (st, reason) -> reasons.add(reason), () -> 0L, false);

        registry.put(UUID.randomUUID(), state(1_000));  // oldest
        registry.put(UUID.randomUUID(), state(2_000));
        registry.put(UUID.randomUUID(), state(3_000));  // triggers eviction of the 1_000 entry

        assertEquals(2, registry.size(), "registry must not exceed max entries");
        assertEquals(1, reasons.size());
        assertEquals(SessionRegistry.REASON_CAPACITY, reasons.get(0));

        // registry.size metric net = +3 -1 = 2; one capacity eviction counted.
        assertEquals(2, TestSupport.longSumTotal(metrics, "guacamole.otel.registry.size"));
        assertEquals(1, TestSupport.longSum(metrics, "guacamole.otel.registry.evictions",
                "guacamole.otel.registry.reason", "capacity"));
    }

    @Test
    void ttlSweepEvictsExpiredEntriesAsTimeout() {
        long[] now = { 100_000L };
        long ttlMillis = 1_000L;
        Config cfg = new Config(true, 10, ttlMillis, minutes(60), false, false, null);
        List<String> reasons = new ArrayList<>();
        SessionRegistry registry = new SessionRegistry(cfg, instruments,
                (st, reason) -> reasons.add(reason), () -> now[0], false);

        UUID uuid = UUID.randomUUID();
        registry.put(uuid, state(100_000L)); // created "now"

        registry.runSweep();                 // now == start: not yet expired
        assertEquals(1, registry.size());
        assertEquals(0, reasons.size());

        now[0] = 100_000L + 2_000L;          // 2s later, ttl is 1s
        registry.runSweep();
        assertEquals(0, registry.size(), "expired entry swept");
        assertEquals(1, reasons.size());
        assertEquals(SessionRegistry.REASON_TIMEOUT, reasons.get(0));
        assertNull(registry.remove(uuid), "entry already gone");

        assertEquals(1, TestSupport.longSum(metrics, "guacamole.otel.registry.evictions",
                "guacamole.otel.registry.reason", "timeout"));
        assertEquals(0, TestSupport.longSumTotal(metrics, "guacamole.otel.registry.size"));
    }

    private static long hours(int h) { return h * 3_600_000L; }
    private static long minutes(int m) { return m * 60_000L; }
}
