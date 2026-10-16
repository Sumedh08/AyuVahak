ALTER TABLE platform_exchange.partner_profile_operation
    DROP CONSTRAINT partner_profile_operation_owner_context_check,
    DROP CONSTRAINT partner_profile_operation_protocol_check;

ALTER TABLE platform_exchange.partner_profile_operation
    ADD CONSTRAINT partner_profile_operation_owner_context_check
        CHECK (owner_context IN ('DIRECTORY', 'REFERRAL', 'TRANSPORT')),
    ADD CONSTRAINT partner_profile_operation_protocol_check
        CHECK (protocol IN ('FHIR_R4_JSON', 'JSON'));

ALTER TABLE platform_exchange.partner_profile_operation
    ADD CONSTRAINT partner_profile_operation_protocol_owner_check
        CHECK ((owner_context = 'TRANSPORT' AND protocol = 'JSON')
            OR (owner_context <> 'TRANSPORT' AND protocol = 'FHIR_R4_JSON'));
