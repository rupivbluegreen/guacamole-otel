# Guacamole receiver

A stock-component OpenTelemetry Collector **receiver** that polls the Apache
Guacamole REST API and reports active-connection metrics. It is the pull-based,
ecosystem-native counterpart to the [guacamole-ext listener extension](../../extension/)
and replaces the unmaintained `tschoonj/guacamole_exporter`.

> Status: **development**. Standalone Go module; for an
> `opentelemetry-collector-contrib` submission the module path and generated
> `internal/metadata` change (see `go.mod` / `metadata.yaml`).

## Metrics

| Metric | Type | Unit | Description |
|---|---|---|---|
| `guacamole.active_connections` | Gauge (int) | `{connection}` | currently active Guacamole connections |

## Configuration

```yaml
receivers:
  guacamole:
    endpoint: http://guacamole.internal:8080/guacamole
    username: monitor
    password: ${env:GUAC_PASSWORD}
    data_source: postgresql        # optional; defaults to the token's data source
    collection_interval: 30s       # scraperhelper.ControllerConfig
    timeout: 10s                   # confighttp.ClientConfig
```

`endpoint` and `username` are required. All standard `confighttp` (TLS, proxy,
headers) and `scraperhelper` (interval, initial_delay) options are supported.

## How it scrapes

1. `POST {endpoint}/api/tokens` with the username/password → auth token + data source.
2. `GET {endpoint}/api/session/data/{data_source}/activeConnections` with the
   `Guacamole-Token` header → the active-connection map; its size is the gauge value.

## Build into a collector

Add it to an [OpenTelemetry Collector Builder](https://opentelemetry.io/docs/collector/custom-collector/)
manifest:

```yaml
receivers:
  - gomod: github.com/guacamole-otel/guacamolereceiver v0.1.0
```

## Develop

```sh
go test ./...
go build ./...
```
