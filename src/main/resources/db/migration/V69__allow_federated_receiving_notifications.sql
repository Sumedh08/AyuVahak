ALTER TABLE platform_notification.notification_projection
    DROP CONSTRAINT notification_projection_resource_type_valid;

ALTER TABLE platform_notification.notification_projection
    ADD CONSTRAINT notification_projection_resource_type_valid
        CHECK (resource_type IN ('referral', 'transfer', 'federated-receiving-request',
            'capacity-reservation', 'transport', 'transport-cancellation',
            'transport-destination-change'));
