ALTER TABLE referral.draft DROP CONSTRAINT draft_state_check;
ALTER TABLE referral.draft
    ADD CONSTRAINT referral_draft_state_check CHECK (state IN ('DRAFT', 'SUBMITTED'));

CREATE TABLE referral.submitted_revision (
    referral_id uuid NOT NULL REFERENCES referral.draft (referral_id) ON DELETE RESTRICT,
    revision_number bigint NOT NULL CHECK (revision_number > 0),
    referral_version bigint NOT NULL CHECK (referral_version > 0),
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
    source_actor_kind varchar(16) NOT NULL CHECK (source_actor_kind IN ('HUMAN', 'SERVICE')),
    source_actor_issuer varchar(256) NOT NULL CHECK (length(btrim(source_actor_issuer)) > 0),
    source_actor_identifier_type varchar(16) NOT NULL,
    source_actor_identifier varchar(512) NOT NULL CHECK (length(btrim(source_actor_identifier)) > 0),
    submitted_by_kind varchar(16) NOT NULL CHECK (submitted_by_kind IN ('HUMAN', 'SERVICE')),
    submitted_by_issuer varchar(256) NOT NULL CHECK (length(btrim(submitted_by_issuer)) > 0),
    submitted_by_identifier_type varchar(16) NOT NULL,
    submitted_by_identifier varchar(512) NOT NULL CHECK (length(btrim(submitted_by_identifier)) > 0),
    submitted_at timestamptz NOT NULL,
    CONSTRAINT submitted_revision_patient_encounter_shape CHECK (
        (encounter_issuer IS NULL AND encounter_resource_type IS NULL AND encounter_identifier IS NULL)
        OR
        (encounter_issuer IS NOT NULL AND length(btrim(encounter_issuer)) > 0
            AND encounter_resource_type IS NOT NULL
            AND encounter_resource_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,63}$'
            AND encounter_identifier IS NOT NULL AND length(btrim(encounter_identifier)) > 0
            AND encounter_identifier = btrim(encounter_identifier)
            AND encounter_identifier !~ '[[:cntrl:]]')
    ),
    CONSTRAINT submitted_revision_time_window CHECK (
        requested_start IS NULL OR requested_end IS NULL OR requested_start < requested_end
    ),
    CONSTRAINT submitted_revision_source_actor_type CHECK (
        (source_actor_kind = 'HUMAN' AND source_actor_identifier_type = 'subject') OR
        (source_actor_kind = 'SERVICE' AND source_actor_identifier_type = 'client_id')
    ),
    CONSTRAINT submitted_revision_submitter_type CHECK (
        (submitted_by_kind = 'HUMAN' AND submitted_by_identifier_type = 'subject') OR
        (submitted_by_kind = 'SERVICE' AND submitted_by_identifier_type = 'client_id')
    ),
    PRIMARY KEY (referral_id, revision_number),
    UNIQUE (referral_id, referral_version)
);

CREATE TABLE referral.submitted_revision_required_care_capability (
    referral_id uuid NOT NULL,
    revision_number bigint NOT NULL,
    capability_code varchar(64) NOT NULL CHECK (capability_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    PRIMARY KEY (referral_id, revision_number, capability_code),
    FOREIGN KEY (referral_id, revision_number)
        REFERENCES referral.submitted_revision (referral_id, revision_number) ON DELETE RESTRICT
);

CREATE TABLE referral.submitted_revision_permitted_geography_code (
    referral_id uuid NOT NULL,
    revision_number bigint NOT NULL,
    geography_code varchar(64) NOT NULL CHECK (geography_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    PRIMARY KEY (referral_id, revision_number, geography_code),
    FOREIGN KEY (referral_id, revision_number)
        REFERENCES referral.submitted_revision (referral_id, revision_number) ON DELETE RESTRICT
);

CREATE FUNCTION referral.reject_submitted_revision_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Submitted Referral revisions are immutable';
END;
$$;

CREATE TRIGGER submitted_revision_is_immutable
    BEFORE UPDATE OR DELETE ON referral.submitted_revision
    FOR EACH ROW EXECUTE FUNCTION referral.reject_submitted_revision_mutation();

CREATE TRIGGER submitted_revision_capabilities_are_immutable
    BEFORE UPDATE OR DELETE ON referral.submitted_revision_required_care_capability
    FOR EACH ROW EXECUTE FUNCTION referral.reject_submitted_revision_mutation();

CREATE TRIGGER submitted_revision_geography_is_immutable
    BEFORE UPDATE OR DELETE ON referral.submitted_revision_permitted_geography_code
    FOR EACH ROW EXECUTE FUNCTION referral.reject_submitted_revision_mutation();
