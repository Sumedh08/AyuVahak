DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM analytics.disclosure_policy_head
        GROUP BY organization_issuer, organization_reference_type, organization_id
        HAVING COUNT(DISTINCT conservative_lineage_id) > 1
    ) THEN
        RAISE EXCEPTION 'Analytics disclosure policy keys for one organization have split conservative lineages';
    END IF;
END;
$$;

ALTER TABLE analytics.disclosure_lineage
    ADD COLUMN organization_issuer VARCHAR(256),
    ADD COLUMN organization_reference_type VARCHAR(64),
    ADD COLUMN organization_id UUID;

UPDATE analytics.disclosure_lineage lineage
SET organization_issuer = policy.organization_issuer,
    organization_reference_type = policy.organization_reference_type,
    organization_id = policy.organization_id
FROM analytics.disclosure_policy_head policy
WHERE policy.policy_key = lineage.policy_key;

ALTER TABLE analytics.disclosure_lineage
    ALTER COLUMN organization_issuer SET NOT NULL,
    ALTER COLUMN organization_reference_type SET NOT NULL,
    ALTER COLUMN organization_id SET NOT NULL,
    ADD CONSTRAINT analytics_disclosure_lineage_organization_type CHECK (
        organization_reference_type = 'Organization'
    ),
    ADD CONSTRAINT analytics_disclosure_lineage_organization_lineage_unique
        UNIQUE (organization_issuer, organization_reference_type, organization_id, lineage_id);

CREATE TABLE analytics.disclosure_privacy_scope (
    organization_issuer VARCHAR(256) NOT NULL,
    organization_reference_type VARCHAR(64) NOT NULL CHECK (organization_reference_type = 'Organization'),
    organization_id UUID NOT NULL,
    conservative_lineage_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_issuer, organization_reference_type, organization_id),
    UNIQUE (
        organization_issuer, organization_reference_type, organization_id, conservative_lineage_id
    ),
    FOREIGN KEY (
        organization_issuer, organization_reference_type, organization_id, conservative_lineage_id
    ) REFERENCES analytics.disclosure_lineage (
        organization_issuer, organization_reference_type, organization_id, lineage_id
    ) ON DELETE RESTRICT
);

INSERT INTO analytics.disclosure_privacy_scope (
    organization_issuer, organization_reference_type, organization_id, conservative_lineage_id, created_at
)
SELECT organization_issuer,
       organization_reference_type,
       organization_id,
       (ARRAY_AGG(conservative_lineage_id ORDER BY policy_key))[1],
       MIN(created_at)
FROM analytics.disclosure_policy_head
GROUP BY organization_issuer, organization_reference_type, organization_id;

ALTER TABLE analytics.disclosure_policy_head
    ADD CONSTRAINT analytics_disclosure_policy_head_privacy_scope_fk
        FOREIGN KEY (
            organization_issuer, organization_reference_type, organization_id, conservative_lineage_id
        ) REFERENCES analytics.disclosure_privacy_scope (
            organization_issuer, organization_reference_type, organization_id, conservative_lineage_id
        ) ON DELETE RESTRICT;

DO $$
DECLARE
    constraint_row RECORD;
BEGIN
    FOR constraint_row IN
        SELECT conname, conrelid::regclass AS relation_name
        FROM pg_constraint
        WHERE contype = 'f'
          AND (
              (
                  conrelid IN (
                      'analytics.disclosure_policy_version'::regclass,
                      'analytics.disclosure_policy_output_lineage'::regclass
                  )
                  AND confrelid = 'analytics.disclosure_lineage'::regclass
              )
              OR (
                  conrelid = 'analytics.disclosure_lineage'::regclass
                  AND confrelid = 'analytics.disclosure_policy_head'::regclass
              )
          )
    LOOP
        EXECUTE format(
            'ALTER TABLE %s DROP CONSTRAINT %I',
            constraint_row.relation_name,
            constraint_row.conname
        );
    END LOOP;
END;
$$;

ALTER TABLE analytics.disclosure_policy_version
    ADD CONSTRAINT analytics_disclosure_policy_version_conservative_lineage_fk
        FOREIGN KEY (conservative_lineage_id)
        REFERENCES analytics.disclosure_lineage (lineage_id) ON DELETE RESTRICT;

ALTER TABLE analytics.disclosure_policy_output_lineage
    ADD CONSTRAINT analytics_disclosure_policy_output_lineage_lineage_fk
        FOREIGN KEY (lineage_id)
        REFERENCES analytics.disclosure_lineage (lineage_id) ON DELETE RESTRICT;

ALTER TABLE analytics.disclosure_lineage
    DROP CONSTRAINT disclosure_lineage_policy_key_lineage_id_key,
    DROP COLUMN policy_key;
