# OpenShift / Kubernetes deployment

Deploys the instrumented Guacamole image + guacd + the collector. PostgreSQL is a
prerequisite (out of scope here).

## Build & push the instrumented image

```sh
docker build -f packaging/docker/Dockerfile -t <registry>/guacamole-otel:0.1.0 .
docker push <registry>/guacamole-otel:0.1.0
# then set that image ref in guacamole.yaml
```

## Deploy

```sh
# 1. secrets (fill in real values first — see secrets.example.yaml)
oc apply -f packaging/openshift/secrets.example.yaml

# 2. everything else
oc apply -k packaging/openshift/
```

## Security context / SCC

Everything runs under the default **restricted-v2** SCC — no elevated SCC, no
`privileged`, no `hostPath`, no fixed UID:

- OpenShift assigns an **arbitrary non-root UID** in the root group. The baked agent
  and extension jars are **world-readable (0644)**, so that UID can read them.
- `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `capabilities: drop:[ALL]`,
  `seccompProfile: RuntimeDefault`. The collector also runs `readOnlyRootFilesystem`.

## guacd log correlation (Gap 2) on a cluster

The systemd bundle reads guacd logs from **journald**; that does not exist in-cluster.
Options, in order of preference:

1. **Cluster log pipeline → OTLP.** If you run OpenShift Logging / an OTLP-capable log
   forwarder, forward guacd's container logs to the collector's OTLP logs endpoint. The
   `transform/guacd` processor (in the ConfigMap) then promotes `guacamole.connection.id`
   exactly as in the systemd bundle.
2. **guacd log sidecar.** Add a `filelog`-based collector sidecar to the guacd pod that
   tails the container log and forwards over OTLP to the main collector.
3. **Skip guacd correlation.** Session telemetry (spans, metrics, auth/session logs)
   still flows over OTLP and is fully useful on its own; only the guacd-log join is lost.

The extension itself needs nothing special here — it emits over OTLP to the collector
Service (`guacamole-otelcol:4317`), set via `OTEL_EXPORTER_OTLP_ENDPOINT`.
