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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Central attribute-key registry and the cardinality guard (guardrails G3, G4).
 *
 * <p>Two rules are load-bearing and enforced here rather than by convention:
 * <ul>
 *   <li><b>G3 — credentials never touch telemetry.</b> There is no generic
 *       object-to-attributes path; callers pass already-extracted, allowlisted
 *       {@code String}s. The {@code Credentials} object and {@code getPassword()}
 *       are never referenced by this class.</li>
 *   <li><b>G4 — metric cardinality allowlist.</b> {@link MetricDimensions} exposes
 *       setters only for the bounded-cardinality dimensions. The unbounded keys
 *       ({@link #TUNNEL_UUID}, {@code guacamole.connection.name}, {@link #CLIENT_ADDRESS})
 *       have no setter and are therefore <em>unattachable to metrics at compile time</em>.
 *       {@link #ENDUSER_ID} is opt-in (default off).</li>
 * </ul>
 *
 * <p>{@code guacamole.connection.name} is deliberately absent: it is not reachable
 * from a {@code TunnelConnectEvent} (VERIFIED, Gate 0.3) and defining a key for it
 * would invite an unbounded metric dimension.
 */
public final class Attributes {

    private Attributes() {}

    // --- Span / log attributes (high-cardinality keys allowed here, never on metrics) ---

    /** Per-tunnel UUID. Span attribute only — never a metric dimension. */
    public static final AttributeKey<String> TUNNEL_UUID =
            AttributeKey.stringKey("guacamole.tunnel.uuid");

    /** guacd connection id (the ready-instruction id); correlation key to guacd logs. */
    public static final AttributeKey<String> CONNECTION_ID =
            AttributeKey.stringKey("guacamole.connection.id");

    /** Remote protocol (rdp/vnc/ssh/...). Bounded — also a legal metric dimension. */
    public static final AttributeKey<String> PROTOCOL =
            AttributeKey.stringKey("guacamole.protocol");

    /** Guacamole datasource (e.g. postgresql). Bounded — also a legal metric dimension. */
    public static final AttributeKey<String> DATASOURCE =
            AttributeKey.stringKey("guacamole.datasource");

    /** Authenticated user identifier. Personal data (§10). Span/log; metric only if opted in. */
    public static final AttributeKey<String> ENDUSER_ID =
            AttributeKey.stringKey("enduser.id");

    /** Client remote address. Personal data (§10). Span/log only — never a metric dimension. */
    public static final AttributeKey<String> CLIENT_ADDRESS =
            AttributeKey.stringKey("client.address");

    /** Session end reason: {@code closed} | {@code timeout}. Bounded — legal metric dimension. */
    public static final AttributeKey<String> END_REASON =
            AttributeKey.stringKey("guacamole.session.end_reason");

    /** Auth outcome: {@code success} | {@code failure}. Bounded — legal metric dimension. */
    public static final AttributeKey<String> AUTH_OUTCOME =
            AttributeKey.stringKey("guacamole.auth.outcome");

    /** Whether an auth-success was a re-auth of an existing session (§9 noise filter). */
    public static final AttributeKey<Boolean> AUTH_EXISTING_SESSION =
            AttributeKey.booleanKey("guacamole.auth.existing_session");

    /** Failure cause type — the Throwable's simple class name only, never its message (G3). */
    public static final AttributeKey<String> AUTH_FAILURE_TYPE =
            AttributeKey.stringKey("guacamole.auth.failure_type");

    /** Self-telemetry: the stage at which an otel error occurred. Bounded metric dimension. */
    public static final AttributeKey<String> OTEL_STAGE =
            AttributeKey.stringKey("guacamole.otel.stage");

    /** Self-telemetry: registry eviction reason ({@code capacity} | {@code timeout}). */
    public static final AttributeKey<String> REGISTRY_REASON =
            AttributeKey.stringKey("guacamole.otel.registry.reason");

    /** Log-record event name (e.g. {@code guacamole.session.connected}). */
    public static final AttributeKey<String> EVENT_NAME =
            AttributeKey.stringKey("event.name");

    /** Values {@code closed} and {@code timeout} for {@link #END_REASON}. */
    public static final String END_REASON_CLOSED = "closed";
    public static final String END_REASON_TIMEOUT = "timeout";

    /**
     * Keys that must NEVER appear as metric dimensions (unbounded cardinality).
     * Used by tests to assert the guard; {@link MetricDimensions} enforces it by
     * simply not offering setters for them.
     */
    public static final Set<AttributeKey<?>> PROHIBITED_METRIC_DIMENSIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    TUNNEL_UUID,
                    CLIENT_ADDRESS,
                    CONNECTION_ID,
                    AttributeKey.stringKey("guacamole.connection.name"))));

    /**
     * Builds a metric-dimension {@link io.opentelemetry.api.common.Attributes}.
     *
     * <p>By construction this can only carry bounded-cardinality dimensions plus,
     * when explicitly opted in, {@code enduser.id}. There is no method to attach
     * {@code guacamole.tunnel.uuid}, {@code guacamole.connection.name}, or
     * {@code client.address} — G4 is enforced at the type level, not by review.
     */
    public static final class MetricDimensions {

        private final AttributesBuilder builder =
                io.opentelemetry.api.common.Attributes.builder();

        private MetricDimensions() {}

        public static MetricDimensions create() {
            return new MetricDimensions();
        }

        public MetricDimensions protocol(String value) {
            return put(PROTOCOL, value);
        }

        public MetricDimensions datasource(String value) {
            return put(DATASOURCE, value);
        }

        public MetricDimensions outcome(String value) {
            return put(AUTH_OUTCOME, value);
        }

        public MetricDimensions endReason(String value) {
            return put(END_REASON, value);
        }

        public MetricDimensions stage(String value) {
            return put(OTEL_STAGE, value);
        }

        public MetricDimensions reason(String value) {
            return put(REGISTRY_REASON, value);
        }

        /**
         * Attaches {@code enduser.id} as a metric dimension only when {@code includeUser}
         * is true (config {@code otel.metrics.include-user}, default off — G4).
         */
        public MetricDimensions enduserId(String value, boolean includeUser) {
            if (includeUser) {
                put(ENDUSER_ID, value);
            }
            return this;
        }

        private MetricDimensions put(AttributeKey<String> key, String value) {
            if (value != null) {
                builder.put(key, value);
            }
            return this;
        }

        public io.opentelemetry.api.common.Attributes build() {
            return builder.build();
        }
    }
}
