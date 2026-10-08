CREATE SCHEMA audit;

CREATE TABLE audit.audit_record (
    id uuid PRIMARY KEY,
    action_kind varchar(32) NOT NULL
        CHECK (action_kind IN ('PROTECTED_MUTATION', 'SENSITIVE_READ', 'SECURITY_OUTCOME')),
    actor_kind varchar(16),
    actor_issuer text,
    actor_reference_type varchar(64),
    actor_reference text,
    scope_kind varchar(16),
    organization_issuer text,
    organization_reference_type varchar(64),
    organization_reference text,
    facility_issuer text,
    facility_reference_type varchar(64),
    facility_reference text,
    action_code varchar(100) NOT NULL
        CHECK (action_code ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    target_type varchar(64) NOT NULL
        CHECK (target_type ~ '^[A-Z][A-Za-z0-9]{0,63}$'),
    target_id uuid,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    outcome varchar(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED')),
    reason_code varchar(80)
        CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    authentication_evidence varchar(40) NOT NULL
        CHECK (authentication_evidence IN (
            'NONE', 'INVALID_CREDENTIAL', 'TRUST_SOURCE_UNAVAILABLE',
            'HUMAN_ACCESS_TOKEN', 'SERVICE_ACCESS_TOKEN', 'CONFIGURED_SERVICE_IDENTITY'
        )),
    authorization_outcome varchar(16) NOT NULL
        CHECK (authorization_outcome IN ('NOT_EVALUATED', 'ALLOWED', 'DENIED')),
    correlation_id varchar(128) NOT NULL
        CHECK (correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT audit_actor_shape CHECK (
        (actor_kind IS NULL AND actor_issuer IS NULL AND actor_reference_type IS NULL AND actor_reference IS NULL)
        OR
        (actor_kind = 'HUMAN' AND actor_issuer IS NOT NULL AND actor_reference_type = 'subject' AND actor_reference IS NOT NULL)
        OR
        (actor_kind = 'SERVICE' AND actor_issuer IS NOT NULL AND actor_reference_type = 'client_id' AND actor_reference IS NOT NULL)
    ),
    CONSTRAINT audit_scope_shape CHECK (
        (scope_kind IS NULL AND organization_issuer IS NULL AND organization_reference_type IS NULL
            AND organization_reference IS NULL AND facility_issuer IS NULL
            AND facility_reference_type IS NULL AND facility_reference IS NULL)
        OR
        (scope_kind = 'PLATFORM' AND organization_issuer IS NULL AND organization_reference_type IS NULL
            AND organization_reference IS NULL AND facility_issuer IS NULL
            AND facility_reference_type IS NULL AND facility_reference IS NULL)
        OR
        (scope_kind = 'ORGANIZATION' AND organization_issuer IS NOT NULL
            AND organization_reference_type = 'Organization' AND organization_reference IS NOT NULL
            AND facility_issuer IS NULL AND facility_reference_type IS NULL AND facility_reference IS NULL)
        OR
        (scope_kind = 'FACILITY' AND organization_issuer IS NOT NULL
            AND organization_reference_type = 'Organization' AND organization_reference IS NOT NULL
            AND facility_issuer IS NOT NULL AND facility_reference_type = 'Facility' AND facility_reference IS NOT NULL)
    ),
    CONSTRAINT audit_decision_consistency CHECK (
        (outcome = 'SUCCEEDED' AND authorization_outcome <> 'DENIED')
        OR outcome <> 'SUCCEEDED'
    )
);

CREATE INDEX audit_record_recorded_at_idx ON audit.audit_record (recorded_at DESC, id);
CREATE INDEX audit_record_correlation_idx ON audit.audit_record (correlation_id, recorded_at DESC);
CREATE INDEX audit_record_actor_idx
    ON audit.audit_record (actor_kind, actor_issuer, actor_reference, recorded_at DESC)
    WHERE actor_kind IS NOT NULL;
