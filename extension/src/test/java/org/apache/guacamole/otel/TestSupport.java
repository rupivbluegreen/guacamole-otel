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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.apache.guacamole.io.GuacamoleReader;
import org.apache.guacamole.io.GuacamoleWriter;
import org.apache.guacamole.net.GuacamoleSocket;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.net.RequestDetails;
import org.apache.guacamole.net.auth.AbstractAuthenticatedUser;
import org.apache.guacamole.net.auth.AbstractAuthenticationProvider;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.AuthenticationProvider;
import org.apache.guacamole.net.auth.Credentials;

/** Test doubles and OTel SDK harness helpers. */
final class TestSupport {

    private TestSupport() {}

    /** Assembles an SDK-backed OpenTelemetry wired to in-memory readers/exporters. */
    static OpenTelemetry sdk(InMemoryMetricReader metrics,
                             SpanExporter spanExporter,
                             InMemoryLogRecordExporter logs) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                        .build())
                .setMeterProvider(SdkMeterProvider.builder()
                        .registerMetricReader(metrics)
                        .build())
                .setLoggerProvider(SdkLoggerProvider.builder()
                        .addLogRecordProcessor(SimpleLogRecordProcessor.create(logs))
                        .build())
                .build();
    }

    /** A GuacamoleSocket that only reveals a protocol (never a ConfiguredGuacamoleSocket). */
    static GuacamoleSocket socket(String protocol) {
        return new GuacamoleSocket() {
            @Override public String getProtocol() { return protocol; }
            @Override public GuacamoleReader getReader() { throw new UnsupportedOperationException(); }
            @Override public GuacamoleWriter getWriter() { throw new UnsupportedOperationException(); }
            @Override public void close() { }
            @Override public boolean isOpen() { return true; }
        };
    }

    /** A minimal tunnel returning a fixed UUID and socket. */
    static GuacamoleTunnel tunnel(UUID uuid, GuacamoleSocket socket) {
        return new GuacamoleTunnel() {
            @Override public GuacamoleReader acquireReader() { throw new UnsupportedOperationException(); }
            @Override public void releaseReader() { }
            @Override public boolean hasQueuedReaderThreads() { return false; }
            @Override public GuacamoleWriter acquireWriter() { throw new UnsupportedOperationException(); }
            @Override public void releaseWriter() { }
            @Override public boolean hasQueuedWriterThreads() { return false; }
            @Override public UUID getUUID() { return uuid; }
            @Override public GuacamoleSocket getSocket() { return socket; }
            @Override public void close() { }
            @Override public boolean isOpen() { return true; }
        };
    }

    static AuthenticationProvider authProvider(String identifier) {
        return new AbstractAuthenticationProvider() {
            @Override public String getIdentifier() { return identifier; }
        };
    }

    static AuthenticatedUser authUser(String identifier, AuthenticationProvider provider,
                                      Credentials credentials) {
        AbstractAuthenticatedUser user = new AbstractAuthenticatedUser() {
            @Override public AuthenticationProvider getAuthenticationProvider() { return provider; }
            @Override public Credentials getCredentials() { return credentials; }
        };
        user.setIdentifier(identifier);
        return user;
    }

    /** Builds Credentials carrying a real password (for the G3 assertion). */
    static Credentials credentials(String username, String password, String remoteAddress) {
        return new Credentials(username, password, requestDetails(remoteAddress));
    }

    /** A RequestDetails backed by a stub HttpServletRequest with just a remote address. */
    static RequestDetails requestDetails(String remoteAddress) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getRemoteAddr":
                case "getRemoteHost":
                    return remoteAddress;
                case "getHeaderNames":
                    return java.util.Collections.emptyEnumeration();
                case "getParameterMap":
                    return java.util.Collections.emptyMap();
                case "getCookies":
                    return null;
                case "getSession":
                    return null;
                default:
                    Class<?> rt = method.getReturnType();
                    if (rt.equals(boolean.class)) return false;
                    if (rt.isPrimitive()) return 0;
                    return null;
            }
        };
        Object request = Proxy.newProxyInstance(
                TestSupport.class.getClassLoader(),
                new Class<?>[]{ javax.servlet.http.HttpServletRequest.class },
                handler);
        return new RequestDetails((javax.servlet.http.HttpServletRequest) request);
    }

    /** Sum of a long counter/updown-counter's points filtered by one string attribute. */
    static long longSum(InMemoryMetricReader reader, String metricName, String attrKey, String attrValue) {
        long total = 0;
        for (io.opentelemetry.sdk.metrics.data.MetricData md : reader.collectAllMetrics()) {
            if (!md.getName().equals(metricName)) {
                continue;
            }
            for (io.opentelemetry.sdk.metrics.data.LongPointData p : md.getLongSumData().getPoints()) {
                if (attrValue.equals(p.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.stringKey(attrKey)))) {
                    total += p.getValue();
                }
            }
        }
        return total;
    }

    /** Net value of a long sum metric across all points (e.g. an UpDownCounter). */
    static long longSumTotal(InMemoryMetricReader reader, String metricName) {
        long total = 0;
        for (io.opentelemetry.sdk.metrics.data.MetricData md : reader.collectAllMetrics()) {
            if (!md.getName().equals(metricName)) {
                continue;
            }
            for (io.opentelemetry.sdk.metrics.data.LongPointData p : md.getLongSumData().getPoints()) {
                total += p.getValue();
            }
        }
        return total;
    }

    /** Sum of all recorded values of a histogram metric. */
    static double histogramSum(InMemoryMetricReader reader, String metricName) {
        double total = 0;
        for (io.opentelemetry.sdk.metrics.data.MetricData md : reader.collectAllMetrics()) {
            if (!md.getName().equals(metricName)) {
                continue;
            }
            for (io.opentelemetry.sdk.metrics.data.HistogramPointData p : md.getHistogramData().getPoints()) {
                total += p.getSum();
            }
        }
        return total;
    }

    /** A SpanExporter that throws on every call (G1 failure injection). */
    static SpanExporter throwingSpanExporter() {
        return new SpanExporter() {
            @Override public io.opentelemetry.sdk.common.CompletableResultCode export(
                    java.util.Collection<io.opentelemetry.sdk.trace.data.SpanData> spans) {
                throw new RuntimeException("boom (export)");
            }
            @Override public io.opentelemetry.sdk.common.CompletableResultCode flush() {
                return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
            }
            @Override public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
                return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
            }
        };
    }
}
