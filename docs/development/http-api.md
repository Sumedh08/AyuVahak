# HTTP API Conventions

The platform API uses a major version in the URI. `GET /api/v1/system-info` is a non-sensitive platform route that reports `serviceName`, `apiMajorVersion`, and `applicationVersion`. Build version comes from Spring Boot build information generated from the Maven project version. Build time is omitted so the metadata does not make otherwise reproducible artifacts vary by build time. Phase 3 adds the protected Directory API under `/api/v1/directory`; the Phase 3 specification is its canonical route and behavior contract.

The API version is a major version in the URI. Compatible additive changes stay under the current major version; an incompatible public contract requires a new URI major version.

The Directory API is the only Volume II business context implemented so far. It exposes Organization, Facility, Location, HealthcareService, CareCapability, FacilityCapabilityAssertion, external-identifier association, and bounded Facility discovery operations. It does not expose Capacity, Referral, Matching, Transfer, or Transport behavior.

Directory requests are authenticated and use a server-validated execution context. Where scope headers are supplied, they identify the requested context but never grant authority by themselves; the caller's registered functional grant and the Directory-owned target facts are checked. Directory mutations require an `Idempotency-Key`, preserve required audit evidence in the same transaction, and use explicit provenance/version preconditions where the operation changes an existing fact. The precise authority, request, response, and error-code contract is in the [Phase 3 Care Service Directory specification](../specs/phase-3-care-service-directory-spec.md).

`GET /api/v1/directory/facilities` supports exact Organization/Facility filters with optional required-capability filters. A geographic search is selected only when longitude, latitude, and radius are all supplied. Partial geographic input and unsupported query keys fail validation. Exact results do not claim distance; nearby results use the documented PostGIS distance ordering and keep unlocated facilities separate. Search does not report live capacity or guarantee acceptance.

The Phase 3 response shape is the first Directory API contract intended for publication. Earlier local implementation shapes are not compatibility commitments; after publication, incompatible changes require a new major URI version.

## Errors

Errors use RFC 9457 Problem Details with `Content-Type: application/problem+json`. AyuVahak adds these members:

- `code`: stable platform error code for machine handling.
- `correlationId`: the request correlation identifier also returned in the response header.
- `validationErrors`: optional entries containing a request field and constraint code. Rejected values and validator messages are not returned.

The platform error-code family includes `MALFORMED_REQUEST`, `VALIDATION_FAILED`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `REQUEST_FAILED`, and `INTERNAL_ERROR`. Directory adds stable context-owned codes documented in the [Phase 3 specification](../specs/phase-3-care-service-directory-spec.md); clients should branch on `code` and `status`, not on the human-readable `title` or `detail`.

Unexpected server errors return a generic detail. Responses do not include exception messages, stack traces, SQL, file paths, credentials, or request URI data. Validation details identify the field and constraint without echoing the rejected value.

## Request correlation

`X-Correlation-ID` is the HTTP request/response header. AyuVahak accepts exactly one incoming value containing 1–128 ASCII letters, digits, periods, underscores, colons, or hyphens, beginning with a letter or digit. Missing, malformed, or repeated values are replaced with a generated UUIDv4. The selected value is returned on successful and error responses and is placed in the `correlationId` logging context for the request. The context is removed when request processing completes, including exceptional completion.

The correlation ID follows an HTTP request chain and helps an operator find related diagnostics. It is distinct from a trace ID, which belongs to tracing instrumentation and may be generated or propagated by that instrumentation. It is also distinct from future domain correlation and causation identifiers, which describe relationships among durable business events. These identifiers must not be treated as authorization credentials or patient identifiers.

Production sources contain no test routes. Test-only routes used to exercise generic validation and exception handling live in test sources and are not part of the deployed API.

The frozen major-version and RFC 9457 decisions are in [Phase 1 Engineering Decisions](../architecture/phase-1-engineering-decisions.md). Privacy and logging boundaries are in [Security and Trust](../architecture/security-and-trust.md).
