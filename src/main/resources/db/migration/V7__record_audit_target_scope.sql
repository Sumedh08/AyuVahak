ALTER TABLE audit.audit_record
    ADD COLUMN target_scope_kind varchar(16),
    ADD COLUMN target_organization_issuer text,
    ADD COLUMN target_organization_reference_type varchar(64),
    ADD COLUMN target_organization_reference text,
    ADD COLUMN target_facility_issuer text,
    ADD COLUMN target_facility_reference_type varchar(64),
    ADD COLUMN target_facility_reference text,
    ADD CONSTRAINT audit_target_scope_shape CHECK (
        (target_scope_kind IS NULL AND target_organization_issuer IS NULL
            AND target_organization_reference_type IS NULL
            AND target_organization_reference IS NULL AND target_facility_issuer IS NULL
            AND target_facility_reference_type IS NULL AND target_facility_reference IS NULL)
        OR
        (target_scope_kind = 'PLATFORM' AND target_organization_issuer IS NULL
            AND target_organization_reference_type IS NULL
            AND target_organization_reference IS NULL AND target_facility_issuer IS NULL
            AND target_facility_reference_type IS NULL AND target_facility_reference IS NULL)
        OR
        (target_scope_kind = 'ORGANIZATION' AND target_organization_issuer IS NOT NULL
            AND target_organization_reference_type = 'Organization'
            AND target_organization_reference IS NOT NULL AND target_facility_issuer IS NULL
            AND target_facility_reference_type IS NULL AND target_facility_reference IS NULL)
        OR
        (target_scope_kind = 'FACILITY' AND target_organization_issuer IS NOT NULL
            AND target_organization_reference_type = 'Organization'
            AND target_organization_reference IS NOT NULL AND target_facility_issuer IS NOT NULL
            AND target_facility_reference_type = 'Facility'
            AND target_facility_reference IS NOT NULL)
    );
