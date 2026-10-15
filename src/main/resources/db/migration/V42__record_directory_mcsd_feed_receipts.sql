CREATE TABLE directory.mcsd_feed_receipt (
    source_issuer TEXT NOT NULL CHECK (length(btrim(source_issuer)) BETWEEN 1 AND 512),
    external_message_id VARCHAR(128) NOT NULL
        CHECK (external_message_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    operation_code VARCHAR(64) NOT NULL CHECK (operation_code = 'mcsd.iti130.feed'),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    original_correlation_id VARCHAR(128) NOT NULL
        CHECK (original_correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    response_outcomes TEXT,
    recorded_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (source_issuer, external_message_id, operation_code),
    CHECK (
        (response_outcomes IS NULL AND completed_at IS NULL) OR
        (response_outcomes IS NOT NULL AND length(response_outcomes) BETWEEN 1 AND 12000
            AND response_outcomes !~ '[^A-Z_,]' AND completed_at IS NOT NULL)
    )
);

CREATE INDEX mcsd_feed_receipt_recorded_at_idx
    ON directory.mcsd_feed_receipt (recorded_at);
