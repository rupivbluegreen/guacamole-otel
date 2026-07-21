# Instrumented Apache Guacamole image: the guacamole webapp + the OpenTelemetry
# Java agent + this extension, baked in. Works under plain Docker and under
# OpenShift's arbitrary-UID model (all baked files are world-readable, non-root).
#
# Build:
#   docker build -f packaging/docker/Dockerfile -t guacamole-otel:0.1.0 .
# (build context = repo root)
#
# Runtime config is standard OTEL_* env — at minimum set OTEL_EXPORTER_OTLP_ENDPOINT.

# --- stage 1: build the extension jar ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY extension/pom.xml ./pom.xml
# warm the dependency cache
RUN mvn -q -B -o dependency:go-offline 2>/dev/null || mvn -q -B dependency:go-offline
COPY extension/src ./src
RUN mvn -q -B package && cp target/guacamole-otel-extension.jar /guacamole-otel-extension.jar

# --- stage 2: fetch the OpenTelemetry Java agent (pinned) ---
FROM curlimages/curl:8.21.0 AS agent
ARG OTEL_AGENT_VERSION=2.29.0
RUN curl -sSL -o /tmp/opentelemetry-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"

# --- stage 3: the instrumented guacamole image ---
FROM guacamole/guacamole:1.6.0

# The base runs as the non-root 'guacamole' user; create the target dirs as root.
# Directories must be 0755 (traversable) and files 0644 (readable) so both the
# guacamole user AND an arbitrary OpenShift UID can read the agent + extension.
# NOTE: do NOT use `COPY --chmod=0644` — it also strips the dirs' execute bit,
# which makes them untraversable and prevents the JVM from loading the agent.
USER root
RUN mkdir -p /opt/otel /etc/guacamole/extensions \
    && chmod 0755 /opt/otel /etc/guacamole /etc/guacamole/extensions
COPY --from=agent /tmp/opentelemetry-javaagent.jar        /opt/otel/opentelemetry-javaagent.jar
COPY --from=build /guacamole-otel-extension.jar           /etc/guacamole/extensions/guacamole-otel-extension.jar
RUN chmod 0644 /opt/otel/opentelemetry-javaagent.jar \
               /etc/guacamole/extensions/guacamole-otel-extension.jar \
    && chmod 0755 /opt/otel /etc/guacamole /etc/guacamole/extensions

# Read extensions from /etc/guacamole; attach the agent. Exporter endpoint is left
# to runtime (OTEL_EXPORTER_OTLP_ENDPOINT).
ENV GUACAMOLE_HOME="/etc/guacamole" \
    JAVA_OPTS="-javaagent:/opt/otel/opentelemetry-javaagent.jar" \
    OTEL_SERVICE_NAME="guacamole" \
    OTEL_TRACES_EXPORTER="otlp" \
    OTEL_METRICS_EXPORTER="otlp" \
    OTEL_LOGS_EXPORTER="otlp" \
    OTEL_EXPORTER_OTLP_PROTOCOL="grpc"

# Restore the non-root base user. OpenShift overrides this with an arbitrary UID.
USER guacamole
