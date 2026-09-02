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
import java.util.concurrent.TimeUnit;

/**
 * Authentication log records (SPEC §9) and the {@code guacamole.auth.attempts}
 * metric. Compliance wants per-attempt evidence, so outcomes are OTel log records,
 * not metrics only.
 *
 * <p>Records are built from an explicit allowlist (guardrail G3): username, remote
 * address, datasource, outcome, and — for failures — the cause's class name (never
 * its message). The {@code Credentials} object and {@code getPassword()} are never
 * touched by this class; callers pass already-extracted, allowlisted strings.
 */
public final class AuthEvents {

    static final String EVENT_SUCCESS = "guacamole.auth.success";
    static final String EVENT_FAILURE = "guacamole.auth.failure";
    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_FAILURE = "failure";

    private final Logger logs;
    private final Instruments instruments;
    private final Config config;

    public AuthEvents(Logger logs, Instruments instruments, Config config) {
        this.logs = logs;
        this.instruments = instruments;
        this.config = config;
    }

    /**
     * A fresh, successful login (re-auth of an existing session is filtered out by the
     * caller — §9). Emits an INFO record and counts one success attempt.
     */
    public void onSuccess(String enduserId, String clientAddress, String datasource,
            long eventEpochMillis) {

        AttributesBuilder attrs = io.opentelemetry.api.common.Attributes.builder()
                .put(Attributes.AUTH_OUTCOME, OUTCOME_SUCCESS);
        putIfPresent(attrs, Attributes.ENDUSER_ID, config.pseudonymizeUser(enduserId));
        putIfPresent(attrs, Attributes.CLIENT_ADDRESS, clientAddress);
        putIfPresent(attrs, Attributes.DATASOURCE, datasource);

        emit(EVENT_SUCCESS, Severity.INFO, attrs.build(), eventEpochMillis,
                "Guacamole authentication succeeded");
        instruments.authAttempts().add(1, Attributes.MetricDimensions.create()
                .outcome(OUTCOME_SUCCESS).datasource(datasource).build());
    }

    /**
     * A failed authentication (empty anonymous attempts are filtered by the caller — §9).
     * Emits a WARN record and counts one failure attempt.
     *
     * @param failureType simple class name of the failure Throwable, or null
     */
    public void onFailure(String enduserId, String clientAddress, String datasource,
            String failureType, long eventEpochMillis) {

        AttributesBuilder attrs = io.opentelemetry.api.common.Attributes.builder()
                .put(Attributes.AUTH_OUTCOME, OUTCOME_FAILURE);
        putIfPresent(attrs, Attributes.ENDUSER_ID, config.pseudonymizeUser(enduserId));
        putIfPresent(attrs, Attributes.CLIENT_ADDRESS, clientAddress);
        putIfPresent(attrs, Attributes.DATASOURCE, datasource);
        putIfPresent(attrs, Attributes.AUTH_FAILURE_TYPE, failureType);

        emit(EVENT_FAILURE, Severity.WARN, attrs.build(), eventEpochMillis,
                "Guacamole authentication failed");
        instruments.authAttempts().add(1, Attributes.MetricDimensions.create()
                .outcome(OUTCOME_FAILURE).datasource(datasource).build());
    }

    private void emit(String eventName, Severity severity,
            io.opentelemetry.api.common.Attributes attrs, long epochMillis, String body) {
        logs.logRecordBuilder()
                .setTimestamp(epochMillis, TimeUnit.MILLISECONDS)
                .setSeverity(severity)
                .setSeverityText(severity.name())
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
