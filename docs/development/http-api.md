# HTTP API Conventions

Phase 1 exposes one production application route: `GET /api/v1/system-info`. It exists to prove the platform's versioning and response conventions; it does not represent a healthcare resource. The response contains only `serviceName`, `apiMajorVersion`, and `applicationVersion`. Build version comes from Spring Boot build information generated from the Maven project version. Build time is omitted so the metadata does not make otherwise reproducible artifacts vary by build time.

The API version is a major version in the URI. Compatible additive changes stay under the current major version; an incompatible public contract requires a new URI major version. This phase defines no business routes.

## Errors

Errors use RFC 9457 Problem Details with `Content-Type: application/problem+json`. AyuVahak adds these members:

- `code`: stable platform error code for machine handling.
- `correlationId`: the request correlation identifier also returned in the response header.
- `validationErrors`: optional entries containing a request field and constraint code. Rejected values and validator messages are not returned.

Current platform codes are `MALFORMED_REQUEST`, `VALIDATION_FAILED`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `REQUEST_FAILED`, and `INTERNAL_ERROR`. They describe only platform HTTP failures; no healthcare business codes are defined in Phase 1. Clients should branch on `code` and `status`, not on the human-readable `title` or `detail`.

Unexpected server errors return a generic detail. Responses do not include exception messages, stack traces, SQL, file paths, credentials, or request URI data. Validation details identify the field and constraint without echoing the rejected value.

## Request correlation

`X-Correlation-ID` is the HTTP request/response header. AyuVahak accepts exactly one incoming value containing 1–128 ASCII letters, digits, periods, underscores, colons, or hyphens, beginning with a letter or digit. Missing, malformed, or repeated values are replaced with a generated UUIDv4. The selected value is returned on successful and error responses and is placed in the `correlationId` logging context for the request. The context is removed when request processing completes, including exceptional completion.

The correlation ID follows an HTTP request chain and helps an operator find related diagnostics. It is distinct from a trace ID, which belongs to tracing instrumentation and may be generated or propagated by that instrumentation. It is also distinct from future domain correlation and causation identifiers, which describe relationships among durable business events. These identifiers must not be treated as authorization credentials or patient identifiers.

Production sources contain no test routes. Test-only routes used to exercise generic validation and exception handling live in test sources and are not part of the deployed API.

The frozen major-version and RFC 9457 decisions are in [Phase 1 Engineering Decisions](../architecture/phase-1-engineering-decisions.md). Privacy and logging boundaries are in [Security and Trust](../architecture/security-and-trust.md).
