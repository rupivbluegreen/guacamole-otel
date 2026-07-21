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

import io.opentelemetry.api.trace.Span;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded {@code tunnelUUID -> SessionState} map (guardrail G6).
 *
 * <p>Connect and close arrive as independent events, so correlating them needs
 * state. That state is strictly bounded: a hard maximum entry count (capacity
 * eviction of the oldest) plus a TTL sweep on a single daemon thread. Abnormal
 * closes — webapp kill, network partition, guacd crash — may never deliver a
 * {@code TunnelCloseEvent}; the sweep is how those eventually surface rather than
 * leaking. In-memory only; restart-lossy by design.
 */
public final class SessionRegistry implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SessionRegistry.class);

    /** Eviction reasons (also the {@code guacamole.otel.registry.reason} dimension). */
    static final String REASON_TIMEOUT = "timeout";
    static final String REASON_CAPACITY = "capacity";

    /** Immutable per-session state held between connect and close. */
    public static final class SessionState {
        private final Span span;
        private final long startEpochMillis;
        private final String protocol;
        private final String datasource;

        public SessionState(Span span, long startEpochMillis, String protocol, String datasource) {
            this.span = span;
            this.startEpochMillis = startEpochMillis;
            this.protocol = protocol;
            this.datasource = datasource;
        }

        public Span span() { return span; }
        public long startEpochMillis() { return startEpochMillis; }
        public String protocol() { return protocol; }
        public String datasource() { return datasource; }
    }

    /** Notified when a session is evicted without a normal close (TTL or capacity). */
    public interface EvictionListener {
        void onEvicted(SessionState state, String reason);
    }

    private final ConcurrentHashMap<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long ttlMillis;
    private final Instruments instruments;
    private final EvictionListener evictionListener;
    private final LongSupplier clock;
    private final ScheduledExecutorService sweeper;

    /** Production constructor: starts the daemon sweep on a schedule. */
    public SessionRegistry(Config config, Instruments instruments, EvictionListener evictionListener) {
        this(config, instruments, evictionListener, System::currentTimeMillis, true);
    }

    /** Test constructor: injectable clock, optional scheduler (drive sweep manually). */
    SessionRegistry(Config config, Instruments instruments, EvictionListener evictionListener,
                    LongSupplier clock, boolean startSweeper) {
        this.maxEntries = config.maxEntries();
        this.ttlMillis = config.ttlMillis();
        this.instruments = instruments;
        this.evictionListener = evictionListener;
        this.clock = clock;
        if (startSweeper) {
            this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "guac-otel-sweeper");
                t.setDaemon(true);
                return t;
            });
            long interval = config.sweepIntervalMillis();
            this.sweeper.scheduleAtFixedRate(this::runSweep, interval, interval, TimeUnit.MILLISECONDS);
        }
        else {
            this.sweeper = null;
        }
    }

    /**
     * Records a new session. If at capacity, evicts the oldest entry first (G6).
     * Bounded work only — no I/O, no unbounded wait (L5).
     */
    public void put(UUID tunnelUuid, SessionState state) {
        synchronized (this) {
            if (sessions.size() >= maxEntries && !sessions.containsKey(tunnelUuid)) {
                evictOldest();
            }
            SessionState previous = sessions.put(tunnelUuid, state);
            if (previous == null) {
                instruments.registrySize().add(1);
            }
        }
    }

    /** Removes and returns the state for a normal close, or null if unknown. */
    public SessionState remove(UUID tunnelUuid) {
        SessionState removed = sessions.remove(tunnelUuid);
        if (removed != null) {
            instruments.registrySize().add(-1);
        }
        return removed;
    }

    public int size() {
        return sessions.size();
    }

    private void evictOldest() {
        Map.Entry<UUID, SessionState> oldest = null;
        for (Map.Entry<UUID, SessionState> e : sessions.entrySet()) {
            if (oldest == null || e.getValue().startEpochMillis() < oldest.getValue().startEpochMillis()) {
                oldest = e;
            }
        }
        if (oldest != null) {
            evict(oldest.getKey(), oldest.getValue(), REASON_CAPACITY);
        }
    }

    /** Evicts expired entries. Package-private so tests can drive it deterministically. */
    void runSweep() {
        try {
            long now = clock.getAsLong();
            for (Map.Entry<UUID, SessionState> e : sessions.entrySet()) {
                if (now - e.getValue().startEpochMillis() >= ttlMillis) {
                    evict(e.getKey(), e.getValue(), REASON_TIMEOUT);
                }
            }
        }
        catch (Throwable t) {
            // Never let the sweep thread die (self-telemetry, not propagation).
            instruments.otelErrors().add(1, Attributes.MetricDimensions.create().stage("sweep").build());
            logger.debug("otel registry sweep suppressed error", t);
        }
    }

    private void evict(UUID key, SessionState state, String reason) {
        if (sessions.remove(key, state)) {
            instruments.registrySize().add(-1);
            instruments.registryEvictions().add(1,
                    Attributes.MetricDimensions.create().reason(reason).build());
            try {
                evictionListener.onEvicted(state, reason);
            }
            catch (Throwable t) {
                instruments.otelErrors().add(1,
                        Attributes.MetricDimensions.create().stage("evict").build());
                logger.debug("otel eviction listener suppressed error", t);
            }
        }
    }

    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }
}
