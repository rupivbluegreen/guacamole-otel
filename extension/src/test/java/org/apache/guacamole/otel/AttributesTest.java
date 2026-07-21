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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.guacamole.otel.Attributes.MetricDimensions;
import org.junit.jupiter.api.Test;

/** Guardrail G4: metric-dimension cardinality allowlist is a code invariant. */
class AttributesTest {

    @Test
    void metricDimensionsCarryOnlyBoundedKeys() {
        io.opentelemetry.api.common.Attributes dims = MetricDimensions.create()
                .protocol("ssh")
                .datasource("postgresql")
                .outcome("success")
                .endReason(Attributes.END_REASON_CLOSED)
                .stage("export")
                .reason("capacity")
                .build();

        Set<AttributeKey<?>> keys = dims.asMap().keySet();
        for (AttributeKey<?> prohibited : Attributes.PROHIBITED_METRIC_DIMENSIONS) {
            assertFalse(keys.contains(prohibited),
                    "prohibited metric dimension attached: " + prohibited.getKey());
        }
        assertEquals("ssh", dims.get(Attributes.PROTOCOL));
        assertEquals("postgresql", dims.get(Attributes.DATASOURCE));
    }

    @Test
    void enduserIdIsOptInAndDefaultsOff() {
        io.opentelemetry.api.common.Attributes off = MetricDimensions.create()
                .enduserId("alice", false)
                .build();
        assertNull(off.get(Attributes.ENDUSER_ID), "enduser.id must be absent when not opted in");

        io.opentelemetry.api.common.Attributes on = MetricDimensions.create()
                .enduserId("alice", true)
                .build();
        assertEquals("alice", on.get(Attributes.ENDUSER_ID));
    }

    @Test
    void nullDimensionValuesAreSkipped() {
        io.opentelemetry.api.common.Attributes dims = MetricDimensions.create()
                .protocol(null)
                .datasource("postgresql")
                .build();
        assertNull(dims.get(Attributes.PROTOCOL));
        assertEquals("postgresql", dims.get(Attributes.DATASOURCE));
    }

    /**
     * Locks the type-level guarantee: MetricDimensions exposes setters ONLY for the
     * bounded dimensions. Adding a setter for tunnel.uuid / connection.name /
     * client.address (or any other) would break this test — that is the point.
     */
    @Test
    void metricDimensionsExposeExactlyTheAllowedSetters() {
        Set<String> setterNames = Arrays.stream(MetricDimensions.class.getDeclaredMethods())
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getReturnType().equals(MetricDimensions.class))
                .map(Method::getName)
                .collect(Collectors.toSet());

        Set<String> expected = new HashSet<>(Arrays.asList(
                "protocol", "datasource", "outcome", "endReason", "stage", "reason",
                "enduserId", "put"));
        assertEquals(expected, setterNames,
                "MetricDimensions setter surface changed — verify no unbounded key was added");
    }

    @Test
    void connectionNameIsNotADefinedKey() {
        // connection.name is intentionally undefined (unreachable, Gate 0.3) yet listed
        // as prohibited so any future re-introduction cannot become a metric dimension.
        assertTrue(Attributes.PROHIBITED_METRIC_DIMENSIONS.stream()
                .anyMatch(k -> k.getKey().equals("guacamole.connection.name")));
    }
}
