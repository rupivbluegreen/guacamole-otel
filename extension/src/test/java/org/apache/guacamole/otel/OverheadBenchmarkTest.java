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

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.Arrays;
import java.util.UUID;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.net.event.TunnelCloseEvent;
import org.apache.guacamole.net.event.TunnelConnectEvent;
import org.junit.jupiter.api.Test;

/**
 * Overhead budget (SPEC §13 / §15.3): &lt; 1 ms added latency per event at p99, zero
 * blocking I/O on the event thread. Measured in isolation (in-memory SDK, no network,
 * no password hashing) so it reflects the listener's own cost, not the login path.
 */
class OverheadBenchmarkTest {

    private static final int WARMUP = 20_000;
    private static final int MEASURE = 50_000;

    @Test
    void perEventOverheadIsWellUnderOneMillisecond() {
        InMemoryMetricReader metrics = InMemoryMetricReader.create();
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
        OpenTelemetry otel = TestSupport.sdk(metrics, spans, logs);
        OtelListener listener = new OtelListener(Config.defaults(), otel);

        // Warm up (JIT).
        for (int i = 0; i < WARMUP; i++) {
            cycle(listener);
        }
        spans.reset();

        long[] samples = new long[MEASURE * 2]; // one timing per handleEvent call
        int s = 0;
        for (int i = 0; i < MEASURE; i++) {
            UUID uuid = UUID.randomUUID();
            GuacamoleTunnel tunnel = TestSupport.tunnel(uuid, TestSupport.socket("ssh"));

            long t0 = System.nanoTime();
            listener.handleEvent(new TunnelConnectEvent(null, null, tunnel));
            samples[s++] = System.nanoTime() - t0;

            long t1 = System.nanoTime();
            listener.handleEvent(new TunnelCloseEvent(null, null, tunnel));
            samples[s++] = System.nanoTime() - t1;

            if ((i & 0x3FF) == 0) {
                spans.reset(); // keep the in-memory exporter list bounded during the run
            }
        }

        Arrays.sort(samples);
        double p50 = samples[(int) (samples.length * 0.50)] / 1_000_000.0;
        double p99 = samples[(int) (samples.length * 0.99)] / 1_000_000.0;
        double p999 = samples[(int) (samples.length * 0.999)] / 1_000_000.0;
        System.out.printf("[overhead] per-event ms  p50=%.4f  p99=%.4f  p99.9=%.4f%n", p50, p99, p999);

        // Budget: p99 < 1 ms. Generous ceiling avoids GC-blip flakiness while still
        // catching gross regressions; the printed numbers show the real headroom.
        assertTrue(p99 < 1.0, "p99 per-event overhead " + p99 + " ms exceeds the 1 ms budget");
    }

    private void cycle(OtelListener listener) {
        UUID uuid = UUID.randomUUID();
        GuacamoleTunnel tunnel = TestSupport.tunnel(uuid, TestSupport.socket("ssh"));
        listener.handleEvent(new TunnelConnectEvent(null, null, tunnel));
        listener.handleEvent(new TunnelCloseEvent(null, null, tunnel));
    }
}
