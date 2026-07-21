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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.environment.LocalEnvironment;
import org.apache.guacamole.properties.BooleanGuacamoleProperty;
import org.apache.guacamole.properties.IntegerGuacamoleProperty;
import org.apache.guacamole.properties.StringGuacamoleProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension configuration, read from {@code guacamole.properties}. Every key is
 * {@code otel.}-prefixed, optional, and has a safe default. Exporter configuration
 * is NOT here — that lives in the OTel Java agent's {@code OTEL_*} environment
 * (agent-bridge, Gate 0.1).
 *
 * <p>Reads are fail-open (guardrail spirit of §13.6): an unparseable value logs a
 * warning once and falls back to the default rather than preventing extension load.
 */
public final class Config {

    private static final Logger logger = LoggerFactory.getLogger(Config.class);

    static final int DEFAULT_MAX_ENTRIES = 10_000;
    static final int DEFAULT_TTL_HOURS = 24;
    static final int DEFAULT_SWEEP_MINUTES = 60;

    private static final BooleanGuacamoleProperty ENABLED = boolProp("otel.enabled");
    private static final IntegerGuacamoleProperty MAX_ENTRIES = intProp("otel.registry.max-entries");
    private static final IntegerGuacamoleProperty TTL_HOURS = intProp("otel.session.ttl-hours");
    private static final IntegerGuacamoleProperty SWEEP_MINUTES = intProp("otel.registry.sweep-interval-minutes");
    private static final BooleanGuacamoleProperty INCLUDE_USER = boolProp("otel.metrics.include-user");
    private static final BooleanGuacamoleProperty HASH_USER = boolProp("otel.attributes.hash-user");
    private static final StringGuacamoleProperty HASH_SALT = strProp("otel.attributes.hash-user-salt");

    private final boolean enabled;
    private final int maxEntries;
    private final long ttlMillis;
    private final long sweepIntervalMillis;
    private final boolean includeUserOnMetrics;
    private final boolean hashUser;
    private final String hashSalt;

    /** Full constructor (test-friendly). Values are taken verbatim. */
    Config(boolean enabled, int maxEntries, long ttlMillis, long sweepIntervalMillis,
           boolean includeUserOnMetrics, boolean hashUser, String hashSalt) {
        this.enabled = enabled;
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
        this.sweepIntervalMillis = sweepIntervalMillis;
        this.includeUserOnMetrics = includeUserOnMetrics;
        this.hashUser = hashUser;
        this.hashSalt = hashSalt;
    }

    /** The all-defaults configuration (also the fallback if the environment is unreadable). */
    public static Config defaults() {
        return new Config(true, DEFAULT_MAX_ENTRIES,
                hours(DEFAULT_TTL_HOURS), minutes(DEFAULT_SWEEP_MINUTES),
                false, false, null);
    }

    /** Loads from the local {@code guacamole.properties}; never throws. */
    public static Config load() {
        try {
            return fromEnvironment(LocalEnvironment.getInstance());
        }
        catch (Throwable t) {
            logger.warn("Could not read OTel extension configuration; using defaults.", t);
            return defaults();
        }
    }

    /** Loads from an explicit environment; each field falls back independently. */
    public static Config fromEnvironment(Environment env) {
        boolean enabled = readBool(env, ENABLED, true);
        int maxEntries = readInt(env, MAX_ENTRIES, DEFAULT_MAX_ENTRIES);
        int ttlHours = readInt(env, TTL_HOURS, DEFAULT_TTL_HOURS);
        int sweepMinutes = readInt(env, SWEEP_MINUTES, DEFAULT_SWEEP_MINUTES);
        boolean includeUser = readBool(env, INCLUDE_USER, false);
        boolean hashUser = readBool(env, HASH_USER, false);
        String salt = readString(env, HASH_SALT, null);
        return new Config(enabled, Math.max(1, maxEntries),
                hours(Math.max(1, ttlHours)), minutes(Math.max(1, sweepMinutes)),
                includeUser, hashUser, salt);
    }

    public boolean isEnabled() { return enabled; }
    public int maxEntries() { return maxEntries; }
    public long ttlMillis() { return ttlMillis; }
    public long sweepIntervalMillis() { return sweepIntervalMillis; }
    public boolean includeUserOnMetrics() { return includeUserOnMetrics; }
    public boolean hashUser() { return hashUser; }
    public String hashSalt() { return hashSalt; }

    /**
     * Applies the §10.2 extension-level pseudonymisation policy to a user identifier.
     * When {@code otel.attributes.hash-user=true}, returns a salted SHA-256 hex digest
     * (stable per user, so per-user analysis survives); otherwise returns the value
     * unchanged. The salt is never logged. Null passes through unchanged.
     */
    public String pseudonymizeUser(String enduserId) {
        if (enduserId == null || !hashUser) {
            return enduserId;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (hashSalt != null) {
                md.update(hashSalt.getBytes(StandardCharsets.UTF_8));
            }
            byte[] digest = md.digest(enduserId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present; if not, fail closed (drop the identifier).
            return null;
        }
    }

    private static long hours(int h) { return h * 3_600_000L; }
    private static long minutes(int m) { return m * 60_000L; }

    private static int readInt(Environment env, IntegerGuacamoleProperty p, int dflt) {
        try {
            Integer v = env.getProperty(p);
            return v != null ? v : dflt;
        }
        catch (GuacamoleException e) {
            logger.warn("Invalid value for \"{}\"; using default {}.", nameOf(p), dflt);
            return dflt;
        }
    }

    private static boolean readBool(Environment env, BooleanGuacamoleProperty p, boolean dflt) {
        try {
            Boolean v = env.getProperty(p);
            return v != null ? v : dflt;
        }
        catch (GuacamoleException e) {
            logger.warn("Invalid value for \"{}\"; using default {}.", nameOf(p), dflt);
            return dflt;
        }
    }

    private static String readString(Environment env, StringGuacamoleProperty p, String dflt) {
        try {
            String v = env.getProperty(p);
            return v != null ? v : dflt;
        }
        catch (GuacamoleException e) {
            return dflt;
        }
    }

    private static String nameOf(org.apache.guacamole.properties.GuacamoleProperty<?> p) {
        return p.getName();
    }

    private static IntegerGuacamoleProperty intProp(String name) {
        return new IntegerGuacamoleProperty() {
            @Override public String getName() { return name; }
        };
    }

    private static BooleanGuacamoleProperty boolProp(String name) {
        return new BooleanGuacamoleProperty() {
            @Override public String getName() { return name; }
        };
    }

    private static StringGuacamoleProperty strProp(String name) {
        return new StringGuacamoleProperty() {
            @Override public String getName() { return name; }
        };
    }
}
