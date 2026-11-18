-- Apply to existing volumes in an approved maintenance window before updating core.
USE `quant_cloud`;

-- Each table is changed in a single ALTER so an interrupted migration can be retried.
SET @zone_chart_sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'market_zone'
     AND COLUMN_NAME IN ('effective_until', 'end_lower_price')) = 2,
  'SELECT 1',
  'ALTER TABLE market_zone ADD COLUMN effective_until BIGINT NULL, ADD COLUMN end_lower_price DECIMAL(24,8) NULL, ADD CONSTRAINT chk_zone_until CHECK (effective_until IS NULL OR (effective_until > effective_from AND effective_until <= 253402300799999)), ADD CONSTRAINT chk_zone_channel_end CHECK (end_lower_price IS NULL OR (effective_until IS NOT NULL AND end_lower_price > 0))'
);
PREPARE zone_chart_migration FROM @zone_chart_sql;
EXECUTE zone_chart_migration;
DEALLOCATE PREPARE zone_chart_migration;

SET @zone_chart_sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'market_zone_revision'
     AND COLUMN_NAME IN ('effective_until', 'end_lower_price')) = 2,
  'SELECT 1',
  'ALTER TABLE market_zone_revision ADD COLUMN effective_until BIGINT NULL, ADD COLUMN end_lower_price DECIMAL(24,8) NULL, ADD CONSTRAINT chk_zone_history_until CHECK (effective_until IS NULL OR (effective_until > effective_from AND effective_until <= 253402300799999)), ADD CONSTRAINT chk_zone_history_channel_end CHECK (end_lower_price IS NULL OR (effective_until IS NOT NULL AND end_lower_price > 0))'
);
PREPARE zone_chart_migration FROM @zone_chart_sql;
EXECUTE zone_chart_migration;
DEALLOCATE PREPARE zone_chart_migration;
