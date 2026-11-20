-- Apply to the QuantCloud database before enabling web alerts on an existing volume.
CREATE TABLE IF NOT EXISTS zone_alert_subscription (
    user_id BIGINT NOT NULL,
    zone_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, zone_id),
    KEY idx_alert_subscriptions_zone (zone_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zone_alert_notice (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    zone_id BIGINT NOT NULL,
    zone_revision BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    analysis_interval VARCHAR(10) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    direction VARCHAR(5) NOT NULL,
    market_time BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    email_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claimed_at BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_signal (user_id, zone_id, zone_revision, kind, direction, market_time),
    KEY idx_alert_delivery (email_status, id),
    KEY idx_alert_inbox (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zone_alert_preference (
    user_id BIGINT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    email_enabled BOOLEAN NOT NULL,
    email VARCHAR(254) NULL,
    enabled_since BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
