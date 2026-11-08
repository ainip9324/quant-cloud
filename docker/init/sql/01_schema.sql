-- QuantCloud 的 MySQL 8.0 新库结构。

CREATE DATABASE IF NOT EXISTS `quant_cloud` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `quant_cloud`;

-- 账户与权限
CREATE TABLE IF NOT EXISTS `users` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(32) NOT NULL,
  `email` VARCHAR(120) NOT NULL,
  `password_hash` VARCHAR(100) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `role` VARCHAR(16) NOT NULL DEFAULT 'VIEWER' COMMENT 'ADMIN/VIEWER',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  UNIQUE KEY `uk_user_email` (`email`),
  CONSTRAINT `chk_users_role` CHECK (`role` IN ('ADMIN', 'VIEWER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 行情、策略与回测
CREATE TABLE IF NOT EXISTS `kline` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `market_type` VARCHAR(16) NOT NULL COMMENT 'SPOT/USDT_FUTURES',
  `symbol` VARCHAR(20) NOT NULL COMMENT '交易对，如 BTCUSDT',
  `interval` VARCHAR(10) NOT NULL COMMENT '周期，如 1h、4h、1day',
  `ts` BIGINT NOT NULL COMMENT 'K线开始时间（毫秒时间戳）',
  `open` DOUBLE NOT NULL COMMENT '开盘价',
  `high` DOUBLE NOT NULL COMMENT '最高价',
  `low` DOUBLE NOT NULL COMMENT '最低价',
  `close` DOUBLE NOT NULL COMMENT '收盘价',
  `volume` DOUBLE NOT NULL COMMENT '成交量',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 核心索引：联合唯一索引，防止重复插入 + 支持按交易对+周期+时间范围查询
  UNIQUE KEY `uk_market_symbol_interval_ts` (`market_type`, `symbol`, `interval`, `ts`),
  CONSTRAINT `chk_kline_market_type` CHECK (`market_type` IN ('SPOT','USDT_FUTURES')),
  -- 辅助索引：按时间倒序查最新
  KEY `idx_ts` (`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线数据表';

CREATE TABLE IF NOT EXISTS `strategy` (
  `id` VARCHAR(50) NOT NULL COMMENT '策略标识，如 turtle',
  `name` VARCHAR(100) NOT NULL COMMENT '策略名称',
  `description` TEXT,
  `class_name` VARCHAR(200) COMMENT '实现类全名',
  `params_schema` JSON COMMENT '参数定义（前端表单生成用）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略定义表';

CREATE TABLE IF NOT EXISTS `backtest_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL DEFAULT 1 COMMENT '提交回测的用户',
  `strategy_id` VARCHAR(50) NOT NULL COMMENT '策略标识',
  `market_type` VARCHAR(16) NOT NULL COMMENT 'SPOT/USDT_FUTURES',
  `symbol` VARCHAR(20) NOT NULL,
  `interval` VARCHAR(10) NOT NULL,
  `start_ts` BIGINT COMMENT '回测起始时间（毫秒）',
  `end_ts` BIGINT COMMENT '回测结束时间（毫秒）',
  `params` JSON COMMENT '策略参数',
  `status` VARCHAR(20) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/RUNNING/SUCCESS/FAILED',
  `result_summary` JSON COMMENT '结果摘要（收益率、交易次数等）',
  `replay_data` JSON DEFAULT NULL COMMENT '回放快照（K线和策略事件）',
  `error_msg` TEXT COMMENT '失败原因',
  `bar_limit` INT DEFAULT 500 COMMENT '回测K线数量上限',
  `execution_token` VARCHAR(64) DEFAULT NULL COMMENT '执行令牌（防旧执行者覆盖新结果）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_backtest_task_market_type` CHECK (`market_type` IN ('SPOT','USDT_FUTURES')),
  KEY `idx_status` (`status`),
  KEY `idx_user_create_time` (`user_id`, `create_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测任务表';

-- AI 复盘
CREATE TABLE IF NOT EXISTS ai_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  input_hash CHAR(64) NOT NULL,
  prompt_version VARCHAR(64) NOT NULL,
  prompt_hash CHAR(64) NOT NULL,
  report_schema_version VARCHAR(32) NOT NULL,
  specification_version VARCHAR(64) NOT NULL,
  specification_hash CHAR(64) NOT NULL,
  model_provider VARCHAR(32) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  actual_model_name VARCHAR(128) NULL,
  attempt_no INT NOT NULL,
  report_json JSON NULL,
  prompt_tokens INT NULL,
  completion_tokens INT NULL,
  total_tokens INT NULL,
  finish_reason VARCHAR(32) NULL,
  latency_ms BIGINT NULL,
  tool_call_count INT NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(512) NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_report_fingerprint_attempt (
    user_id, task_id, input_hash, prompt_version, prompt_hash, report_schema_version,
    specification_version, specification_hash, model_provider, model_name, attempt_no
  ),
  KEY idx_ai_report_user_task_create (user_id, task_id, create_time),
  KEY idx_ai_report_status_update (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 共享交易区域
CREATE TABLE IF NOT EXISTS `market_zone_scope` (
  `market_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `symbol` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `analysis_interval` VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`market_type`, `symbol`, `analysis_interval`),
  CONSTRAINT `chk_zone_scope_market` CHECK (`market_type` = 'USDT_FUTURES'),
  CONSTRAINT `chk_zone_scope_symbol` CHECK (`symbol` IN ('BTCUSDT','ETHUSDT')),
  CONSTRAINT `chk_zone_scope_interval` CHECK (`analysis_interval` IN ('15min','1h','4h','1day'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `market_zone` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `market_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `symbol` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `analysis_interval` VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `lower_price` DECIMAL(24,8) NOT NULL,
  `upper_price` DECIMAL(24,8) NOT NULL,
  `effective_from` BIGINT NOT NULL,
  `note` VARCHAR(500) NOT NULL DEFAULT '',
  `lifecycle` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `revision` BIGINT NOT NULL,
  `created_by` BIGINT NOT NULL,
  `updated_by` BIGINT NOT NULL,
  `created_at` BIGINT NOT NULL,
  `updated_at` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_zone_scope_lifecycle` (`market_type`,`symbol`,`analysis_interval`,`lifecycle`,`id`),
  CONSTRAINT `fk_zone_scope` FOREIGN KEY (`market_type`,`symbol`,`analysis_interval`)
    REFERENCES `market_zone_scope` (`market_type`,`symbol`,`analysis_interval`),
  CONSTRAINT `chk_zone_prices` CHECK (`lower_price` > 0 AND `upper_price` > `lower_price`),
  CONSTRAINT `chk_zone_effective` CHECK (`effective_from` > 0 AND `effective_from` <= 253402300799999),
  CONSTRAINT `chk_zone_lifecycle` CHECK (`lifecycle` IN ('ACTIVE','DISABLED','ARCHIVED')),
  CONSTRAINT `chk_zone_revision` CHECK (`revision` > 0),
  CONSTRAINT `chk_zone_actors` CHECK (`created_by` > 0 AND `updated_by` > 0),
  CONSTRAINT `chk_zone_timestamps` CHECK (`created_at` > 0 AND `updated_at` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `market_zone_revision` (
  `zone_id` BIGINT NOT NULL,
  `market_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `symbol` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `analysis_interval` VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `lower_price` DECIMAL(24,8) NOT NULL,
  `upper_price` DECIMAL(24,8) NOT NULL,
  `effective_from` BIGINT NOT NULL,
  `note` VARCHAR(500) NOT NULL DEFAULT '',
  `lifecycle` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `revision` BIGINT NOT NULL,
  `created_by` BIGINT NOT NULL,
  `updated_by` BIGINT NOT NULL,
  `created_at` BIGINT NOT NULL,
  `updated_at` BIGINT NOT NULL,
  PRIMARY KEY (`zone_id`,`revision`),
  CONSTRAINT `fk_zone_revision_zone` FOREIGN KEY (`zone_id`) REFERENCES `market_zone` (`id`),
  CONSTRAINT `chk_zone_history_prices` CHECK (`lower_price` > 0 AND `upper_price` > `lower_price`),
  CONSTRAINT `chk_zone_history_effective` CHECK (`effective_from` > 0 AND `effective_from` <= 253402300799999),
  CONSTRAINT `chk_zone_history_lifecycle` CHECK (`lifecycle` IN ('ACTIVE','DISABLED','ARCHIVED')),
  CONSTRAINT `chk_zone_history_revision` CHECK (`revision` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 区域监控与事件
CREATE TABLE IF NOT EXISTS `market_monitor_cursor` (
  `market_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `symbol` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `analysis_interval` VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `feed_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  `source_sequence` BIGINT NOT NULL DEFAULT 0,
  `price_time` BIGINT NULL,
  `price_received_at` BIGINT NULL,
  `source_emitted_at` BIGINT NULL,
  `last_price` DECIMAL(38,18) NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `baseline_required` BOOLEAN NOT NULL,
  `recorded_at` BIGINT NOT NULL,
  PRIMARY KEY (`market_type`,`symbol`,`analysis_interval`),
  CONSTRAINT `fk_monitor_cursor_scope` FOREIGN KEY (`market_type`,`symbol`,`analysis_interval`)
    REFERENCES `market_zone_scope` (`market_type`,`symbol`,`analysis_interval`),
  CONSTRAINT `chk_monitor_cursor_status` CHECK (`status` IN ('STOPPED','CONNECTING','RECONNECTING','RECOVERING','LIVE','DEGRADED')),
  CONSTRAINT `chk_monitor_cursor_sequence` CHECK (`source_sequence` >= 0),
  CONSTRAINT `chk_monitor_cursor_price` CHECK (`last_price` IS NULL OR `last_price` > 0),
  CONSTRAINT `chk_monitor_cursor_recorded` CHECK (`recorded_at` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `market_zone_observation` (
  `zone_id` BIGINT NOT NULL,
  `zone_revision` BIGINT NOT NULL,
  `state` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `state_price` DECIMAL(38,18) NOT NULL,
  `state_market_time` BIGINT NOT NULL,
  `feed_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_sequence` BIGINT NOT NULL,
  `recorded_at` BIGINT NOT NULL,
  PRIMARY KEY (`zone_id`),
  KEY `idx_observation_revision` (`zone_id`,`zone_revision`),
  CONSTRAINT `fk_observation_revision` FOREIGN KEY (`zone_id`,`zone_revision`) REFERENCES `market_zone_revision` (`zone_id`,`revision`),
  CONSTRAINT `chk_observation_state` CHECK (`state` IN ('ABOVE','APPROACHING_FROM_ABOVE','INSIDE','APPROACHING_FROM_BELOW','BELOW')),
  CONSTRAINT `chk_observation_price` CHECK (`state_price` > 0),
  CONSTRAINT `chk_observation_times` CHECK (`state_market_time` > 0 AND `recorded_at` > 0 AND `source_sequence` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `market_zone_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `zone_id` BIGINT NOT NULL,
  `zone_revision` BIGINT NOT NULL,
  `market_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `symbol` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `analysis_interval` VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `from_state` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `to_state` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `observed_price` DECIMAL(38,18) NOT NULL,
  `market_time` BIGINT NOT NULL,
  `recorded_at` BIGINT NOT NULL,
  `feed_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_sequence` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_zone_event_source` (`zone_id`,`zone_revision`,`feed_id`,`source_sequence`),
  KEY `idx_zone_event_scope` (`market_type`,`symbol`,`analysis_interval`,`id`),
  KEY `idx_zone_event_zone_id` (`zone_id`,`id`),
  CONSTRAINT `fk_zone_event_revision` FOREIGN KEY (`zone_id`,`zone_revision`) REFERENCES `market_zone_revision` (`zone_id`,`revision`),
  CONSTRAINT `fk_zone_event_scope` FOREIGN KEY (`market_type`,`symbol`,`analysis_interval`)
    REFERENCES `market_zone_scope` (`market_type`,`symbol`,`analysis_interval`),
  CONSTRAINT `chk_zone_event_from` CHECK (`from_state` IN ('ABOVE','APPROACHING_FROM_ABOVE','INSIDE','APPROACHING_FROM_BELOW','BELOW')),
  CONSTRAINT `chk_zone_event_to` CHECK (`to_state` IN ('ABOVE','APPROACHING_FROM_ABOVE','INSIDE','APPROACHING_FROM_BELOW','BELOW')),
  CONSTRAINT `chk_zone_event_change` CHECK (`from_state` <> `to_state` AND `event_code` = 'ZONE_STATE_CHANGED'),
  CONSTRAINT `chk_zone_event_price` CHECK (`observed_price` > 0),
  CONSTRAINT `chk_zone_event_times` CHECK (`market_time` > 0 AND `recorded_at` > 0 AND `source_sequence` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 共享盘面简报
CREATE TABLE IF NOT EXISTS `market_briefing` (
  `id` TINYINT UNSIGNED NOT NULL,
  `outlook` VARCHAR(1000) NOT NULL DEFAULT '',
  `events` VARCHAR(1000) NOT NULL DEFAULT '',
  `risks` VARCHAR(1000) NOT NULL DEFAULT '',
  `revision` BIGINT NOT NULL DEFAULT 0,
  `updated_at` BIGINT NULL,
  `updated_by` BIGINT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_market_briefing_singleton` CHECK (`id` = 1),
  CONSTRAINT `chk_market_briefing_revision` CHECK (`revision` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
