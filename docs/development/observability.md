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

## Production SLI/SLO declarations and capacity evidence

Production profiles require a complete deployment-owned SLI plan. The fixed `ServiceLevelIndicator` catalogue identifies the supported measurement, unit, direction, service scope, and bounded label vocabulary. It does not set a universal target or claim that every indicator has a deployment-specific SLO. Each catalogue item must be represented by either an objective or an explicit omission rationale; startup rejects incomplete or duplicate coverage. An objective records its target, measurement window, scope, failure definition, exclusions, alert threshold, accountable owner, and response. Targets belong to the operator's service and workload envelope, not to a platform-wide availability guarantee.

The only catalogue label is `route_class`, and its values are fixed to `read`, `write`, `admin`, `integration`, or `other`. Resource, case, patient, facility, peer, snapshot, and credential identifiers are not permitted metric labels. The catalogue is metadata and an SLO configuration contract; it does not imply that a Prometheus time series or alert rule exists for every catalogue entry. Operators must verify the underlying signal and dashboard/alert before declaring it an active objective.

Example configuration for one synthetic API availability objective:

```yaml
ayuvahak:
  production:
    sli:
      objectives:
        - indicator: API_AVAILABILITY
          target: 0.995
          window: 30d
          service-scope: API
          failure-definition: Eligible synthetic API requests returning 5xx.
          exclusions:
            - Synthetic client cancellation before request admission.
          alert-threshold: 0.98
          accountable-owner: Synthetic operations team
          response: Follow the synthetic API incident runbook.
      omitted-rationales:
        # Every other catalogue entry needs a rationale or its own objective.
        API_LATENCY: Synthetic profile has not selected this objective.
```

The sample is intentionally incomplete and cannot activate a production profile on its own. No target should be copied as a service guarantee. The complete configuration is deployment-specific.

The local `SyntheticBenchmarkRunner` accepts a bounded declaration and a workload adapter supplied by a test or local benchmark harness. Use synthetic scenarios only. A declaration captures source revision, Java and database versions, CPU/memory, workload scale (organizations, facilities, active Referrals, Transfers/day, Capacity observations/minute, SSE clients, federation peers, Analytics indicators, periods, and exports), warm/cold condition, warm-up count, measured-operation count, and repetitions. The run result reports p50/p95/p99 latency, throughput, errors, peak CPU/memory/connections, query count, up to 100 query-plan SHA-256 fingerprints, and maximum durable-queue backlog age. It retains no operation exception text, case identifier, or SQL plan body. Elapsed duration uses a monotonic clock; wall-clock timestamps are provenance only.

The runner is bounded to one million total operations and a finite plan-fingerprint set. It does not call external services, emit metrics, persist benchmark records, or claim scale. Store sanitized result evidence with the declaration and environment for the tested revision; compare only runs with stated workload and environment, and report no capacity beyond the measured envelope. Benchmark results are evidence for an operator-selected SLO, not a default target or a substitute for an isolated production-profile test.

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
