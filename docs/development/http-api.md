# HTTP API Conventions

The platform API uses a major version in the URI. `GET /api/v1/system-info` is a non-sensitive platform route that reports `serviceName`, `apiMajorVersion`, and `applicationVersion`. Build version comes from Spring Boot build information generated from the Maven project version. Build time is omitted so the metadata does not make otherwise reproducible artifacts vary by build time. The Volume II API has protected Directory, Capacity, Referral, Matching, and Transfer routes. Their phase specifications are the canonical route and behavior contracts.

The API version is a major version in the URI. Compatible additive changes stay under the current major version; an incompatible public contract requires a new URI major version.

The implemented Volume II contexts are Directory, Capacity, Referral, Matching, and Transfer. Directory exposes Organization, Facility, Location, HealthcareService, CareCapability, FacilityCapabilityAssertion, external-identifier association, and bounded Facility discovery operations. Capacity exposes attributed observations and readiness projections. Referral exposes its coordination workflow and patient-context references. Matching exposes advisory recommendation creation, history/retrieval, and applicability checks. Transfer exposes journey/request commands, distinct receiving assessments and acceptance, reservation coordination through Capacity's owner contract, transfer progress, and participant-scoped views. Phase 10 exposes bounded Transport request, status/evidence, configured provider-operation, cancellation, destination-change, and scoped reconciliation APIs. Transport does not implement fleet management, route optimization, GPS tracking, or autonomous dispatch; the [Phase 10 specification](../specs/phase-10-emergency-transport-coordination-spec.md) and [acceptance record](../reviews/phase-10-acceptance.md) document its boundaries and evidence.

Context APIs are authenticated and use a server-validated execution context. Where scope headers are supplied, they identify the requested context but never grant authority by themselves; the caller's registered functional grant and owner-held target facts are checked. Mutations follow the owning context's idempotency, audit, provenance, and version-precondition contract. See the relevant phase specification for the precise authority, request, response, and error-code contract: [Phase 3 Directory](../specs/phase-3-care-service-directory-spec.md), [Phase 4 Capacity](../specs/phase-4-capacity-readiness-spec.md), [Phase 5 Referral](../specs/phase-5-referral-spec.md), [Phase 6 Matching](../specs/phase-6-matching-recommendation-spec.md), and [Phase 7 Transfer](../specs/phase-7-transfer-coordination-spec.md).

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
