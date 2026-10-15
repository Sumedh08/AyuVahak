ALTER TABLE platform_exchange.partner_profile_version
    ADD COLUMN token_signing_keys_reference TEXT;

ALTER TABLE platform_exchange.partner_profile_version
    ADD CONSTRAINT partner_profile_token_signing_keys_reference_check
    CHECK (
        (authentication_method IN ('OAUTH_CLIENT_CREDENTIALS', 'OAUTH_PRIVATE_KEY_JWT')
            AND token_signing_keys_reference IS NOT NULL
            AND length(btrim(token_signing_keys_reference)) BETWEEN 1 AND 256)
        OR (authentication_method NOT IN ('OAUTH_CLIENT_CREDENTIALS', 'OAUTH_PRIVATE_KEY_JWT')
            AND token_signing_keys_reference IS NULL)
    ) NOT VALID;
