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

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The seven instruments of SPEC §8, with the explicit session-duration histogram
 * boundaries of §8.2 (defaults are tuned for sub-second HTTP latency and are useless
 * for sessions measured in minutes to hours).
 */
public final class Instruments {

    /** Instrumentation scope name (shared with the tracer/logger). */
    public static final String SCOPE = "org.apache.guacamole.otel";

    /** Session-duration bucket boundaries, in seconds (§8.2). */
    static final List<Double> DURATION_BUCKETS_SECONDS = Collections.unmodifiableList(
            Arrays.asList(10d, 60d, 300d, 900d, 1800d, 3600d, 7200d, 14400d, 28800d, 86400d));

    private final LongUpDownCounter sessionActive;
    private final LongCounter sessionStarted;
    private final DoubleHistogram sessionDuration;
    private final LongCounter authAttempts;
    private final LongCounter otelErrors;
    private final LongUpDownCounter registrySize;
    private final LongCounter registryEvictions;

    public Instruments(Meter meter) {

        sessionActive = meter.upDownCounterBuilder("guacamole.session.active")
                .setUnit("{session}")
                .setDescription("Currently active Guacamole sessions.")
                .build();

        sessionStarted = meter.counterBuilder("guacamole.session.started")
                .setUnit("{session}")
                .setDescription("Total Guacamole sessions started.")
                .build();

        sessionDuration = meter.histogramBuilder("guacamole.session.duration")
                .setUnit("s")
                .setDescription("Guacamole session duration.")
                .setExplicitBucketBoundariesAdvice(DURATION_BUCKETS_SECONDS)
                .build();

        authAttempts = meter.counterBuilder("guacamole.auth.attempts")
                .setUnit("{attempt}")
                .setDescription("Authentication attempts by outcome.")
                .build();

        otelErrors = meter.counterBuilder("guacamole.otel.errors")
                .setUnit("{error}")
                .setDescription("Errors suppressed within the OTel listener, by stage.")
                .build();

        registrySize = meter.upDownCounterBuilder("guacamole.otel.registry.size")
                .setUnit("{entry}")
                .setDescription("Entries currently held in the session registry.")
                .build();

        registryEvictions = meter.counterBuilder("guacamole.otel.registry.evictions")
                .setUnit("{entry}")
                .setDescription("Session-registry evictions, by reason.")
                .build();
    }

    public LongUpDownCounter sessionActive() { return sessionActive; }
    public LongCounter sessionStarted() { return sessionStarted; }
    public DoubleHistogram sessionDuration() { return sessionDuration; }
    public LongCounter authAttempts() { return authAttempts; }
    public LongCounter otelErrors() { return otelErrors; }
    public LongUpDownCounter registrySize() { return registrySize; }
    public LongCounter registryEvictions() { return registryEvictions; }
}
