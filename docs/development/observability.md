# Operational Observability

Phase 1 uses Spring Boot Actuator, Micrometer, Spring Boot's Logstash JSON console format, and Micrometer Tracing with OpenTelemetry. It exposes operational signals for the platform substrate only; it defines no healthcare metrics or patient-level telemetry.

## Management paths

The application exposes only these read-only HTTP management endpoints:

| Path | Purpose |
| --- | --- |
| `/actuator/health` | Overall health status; component details are hidden. |
| `/actuator/health/liveness` | Whether the process is alive and able to continue. It does not depend on PostgreSQL. |
| `/actuator/health/readiness` | Whether the application can serve database-dependent work. It includes the database health contributor. |
| `/actuator/prometheus` | Scrape-format JVM, HTTP, and database-pool metrics. It does not push metrics to a backend. |

Actuator endpoints use an explicit allowlist; environment, logger-control, shutdown, heap, and other management endpoints are not exposed. The listener shares the application port in this phase. Authentication is out of scope, so deployments must keep management access on a trusted operator network and must not publish it to an untrusted network. Health details and components stay hidden in HTTP responses.

Liveness describes process-local health. A failed external system must not cause a liveness failure and restart loop. Readiness includes PostgreSQL because the Phase 1 application depends on it for its platform operations. Optional integrations added in later phases must be added to readiness only when they are required for serving the deployment's work.

## Request logs and privacy

Console logs use Spring Boot's built-in Logstash JSON format. The request log records only:

- event name,
- HTTP method,
- matched route template (or `unmatched`),
- response status,
- elapsed milliseconds,
- correlation ID from the request logging context.

The filter does not read or log request/response bodies, headers, cookies, raw request URIs, or query strings. Route templates come from Spring MVC's matched-handler metadata, not the incoming path. Do not add patient identifiers, clinical context, credentials, tokens, or other sensitive values to log messages, metric names, labels, span attributes, or baggage. Keep metric labels low-cardinality and independent of patient/case identifiers.

Phase 3 Directory requests retain these same defaults. Their search filters and identifier values are not logged, and Directory does not add patient-level metrics or payload-bearing telemetry.

`X-Correlation-ID` is generated or validated by the HTTP correlation filter and is returned to the caller and placed in the logging context for that request. When tracing is active, Spring Boot also places trace and span IDs in structured logs. A correlation ID is not a trace ID, a patient identifier, or a future domain correlation/causation ID. See [HTTP API conventions](http-api.md) for the request-header rules.

## Tracing and export

Micrometer Tracing and the OpenTelemetry bridge provide trace-compatible request instrumentation. Local startup does not require an OpenTelemetry collector. OTLP trace export is disabled by default; an operator may enable it with `AYUVAHAK_OTLP_TRACING_ENABLED=true` and configure a deployment-controlled collector endpoint using Spring Boot's `management.opentelemetry.tracing.export.otlp.endpoint` configuration. Do not commit endpoint credentials or send telemetry to an unapproved destination.

OTLP metrics export is disabled. Metrics remain available from `/actuator/prometheus` for an operator-selected scraper. AyuVahak does not include or require a telemetry backend.

## Local checks

With the application and its disposable PostgreSQL/PostGIS dependency running, use:

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/prometheus
```

The integration suite uses the local disposable PostGIS container to verify health/readiness, liveness, the metrics scrape, and that unapproved Actuator endpoints are absent. The HTTP integration test captures the standard request log and verifies correlation and route metadata are present while synthetic body, query, authorization-header, and cookie probes are absent.

The global scenario requirements and phase activation policy are in [Quality Attributes](../architecture/quality-attributes.md). Security and data-minimization requirements are in [Security and Trust](../architecture/security-and-trust.md).

Framework behavior was checked against the official [Spring Boot 4.1.1 Actuator endpoint](https://docs.spring.io/spring-boot/4.1/reference/actuator/endpoints.html), [health probe](https://docs.spring.io/spring-boot/4.1/reference/actuator/endpoints.html), [tracing](https://docs.spring.io/spring-boot/4.1/reference/actuator/tracing.html), [metrics](https://docs.spring.io/spring-boot/4.1/reference/actuator/metrics.html), and [structured logging](https://docs.spring.io/spring-boot/4.1/reference/features/logging.html) documentation, retrieved 2026-10-08.
