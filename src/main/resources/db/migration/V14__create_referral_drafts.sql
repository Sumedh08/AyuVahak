CREATE SCHEMA referral;

CREATE TABLE referral.draft (
    referral_id uuid PRIMARY KEY,
    state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (state = 'DRAFT'),
    origin_organization_id uuid NOT NULL,
    origin_facility_id uuid,
    patient_issuer varchar(256) NOT NULL CHECK (length(btrim(patient_issuer)) > 0),
    patient_resource_type varchar(64) NOT NULL CHECK (patient_resource_type = 'Patient'),
    patient_identifier varchar(512) NOT NULL CHECK (
        length(btrim(patient_identifier)) > 0
        AND patient_identifier = btrim(patient_identifier)
        AND patient_identifier !~ '[[:cntrl:]]'
    ),
    encounter_issuer varchar(256),
    encounter_resource_type varchar(64),
    encounter_identifier varchar(512),
    requested_start timestamptz,
    requested_end timestamptz,
    selected_destination_organization_id uuid,
    priority_code varchar(40) CHECK (
        priority_code IS NULL OR priority_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'
    ),
    created_by_kind varchar(16) NOT NULL CHECK (created_by_kind IN ('HUMAN', 'SERVICE')),
    created_by_issuer varchar(256) NOT NULL CHECK (length(btrim(created_by_issuer)) > 0),
    created_by_identifier_type varchar(16) NOT NULL,
    created_by_identifier varchar(512) NOT NULL CHECK (length(btrim(created_by_identifier)) > 0),
    updated_by_kind varchar(16) NOT NULL CHECK (updated_by_kind IN ('HUMAN', 'SERVICE')),
    updated_by_issuer varchar(256) NOT NULL CHECK (length(btrim(updated_by_issuer)) > 0),
    updated_by_identifier_type varchar(16) NOT NULL,
    updated_by_identifier varchar(512) NOT NULL CHECK (length(btrim(updated_by_identifier)) > 0),
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT referral_draft_patient_encounter_shape CHECK (
        (encounter_issuer IS NULL AND encounter_resource_type IS NULL AND encounter_identifier IS NULL)
        OR
        (encounter_issuer IS NOT NULL AND length(btrim(encounter_issuer)) > 0
            AND encounter_resource_type IS NOT NULL
            AND encounter_resource_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,63}$'
            AND encounter_identifier IS NOT NULL AND length(btrim(encounter_identifier)) > 0
            AND encounter_identifier = btrim(encounter_identifier)
            AND encounter_identifier !~ '[[:cntrl:]]')
    ),
    CONSTRAINT referral_draft_time_window CHECK (
        requested_start IS NULL OR requested_end IS NULL OR requested_start < requested_end
    ),
    CONSTRAINT referral_draft_creator_type CHECK (
        (created_by_kind = 'HUMAN' AND created_by_identifier_type = 'subject') OR
        (created_by_kind = 'SERVICE' AND created_by_identifier_type = 'client_id')
    ),
    CONSTRAINT referral_draft_updater_type CHECK (
        (updated_by_kind = 'HUMAN' AND updated_by_identifier_type = 'subject') OR
        (updated_by_kind = 'SERVICE' AND updated_by_identifier_type = 'client_id')
    )
);

CREATE INDEX referral_draft_origin_idx
    ON referral.draft (origin_organization_id, origin_facility_id, updated_at DESC, referral_id);

CREATE TABLE referral.required_care_capability (
    referral_id uuid NOT NULL REFERENCES referral.draft (referral_id) ON DELETE RESTRICT,
    capability_code varchar(64) NOT NULL CHECK (capability_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    PRIMARY KEY (referral_id, capability_code)
);

CREATE TABLE referral.permitted_geography_code (
    referral_id uuid NOT NULL REFERENCES referral.draft (referral_id) ON DELETE RESTRICT,
    geography_code varchar(64) NOT NULL CHECK (geography_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    PRIMARY KEY (referral_id, geography_code)
);

CREATE TABLE referral.command_receipt (
    scope_digest char(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest char(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state varchar(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status integer,
    result_reference uuid,
    result_version bigint,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT referral_receipt_result_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND response_status IS NULL
            AND result_reference IS NULL AND result_version IS NULL)
        OR
        (receipt_state = 'COMPLETED' AND response_status BETWEEN 200 AND 299
            AND result_reference IS NOT NULL AND result_version > 0)
    )
);
