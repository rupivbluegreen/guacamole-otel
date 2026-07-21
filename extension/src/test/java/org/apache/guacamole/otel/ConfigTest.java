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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Safe defaults and the §10.2 pseudonymisation policy. */
class ConfigTest {

    @Test
    void defaultsAreSafe() {
        Config c = Config.defaults();
        assertTrue(c.isEnabled());
        assertEquals(Config.DEFAULT_MAX_ENTRIES, c.maxEntries());
        assertEquals(24L * 3_600_000L, c.ttlMillis());
        assertFalse(c.includeUserOnMetrics(), "enduser.id on metrics off by default (G4)");
        assertFalse(c.hashUser(), "identifiable by default (§10.2)");
    }

    @Test
    void pseudonymiseUserPassesThroughWhenDisabled() {
        Config c = Config.defaults();
        assertEquals("alice", c.pseudonymizeUser("alice"));
        assertNull(c.pseudonymizeUser(null));
    }

    @Test
    void pseudonymiseUserHashesStablyWhenEnabled() {
        Config c = new Config(true, 10, 1000, 1000, false, true, "pepper");
        String h1 = c.pseudonymizeUser("alice");
        String h2 = c.pseudonymizeUser("alice");
        assertNotEquals("alice", h1, "value must be hashed");
        assertEquals(h1, h2, "same user hashes stably");
        assertEquals(64, h1.length(), "SHA-256 hex is 64 chars");
        assertNotEquals(h1, c.pseudonymizeUser("bob"));
        assertNull(c.pseudonymizeUser(null));
    }
}
