-- ==============================================================================
-- PharmaTrack MES & eBR - Dedicated AI Batch Analysis Reports (Flyway V5)
-- ==============================================================================
-- Additive InnoDB migration introducing persistent, independent AI Batch Analysis
-- Reports for PRODUCTION_SUPERVISOR and authorized operational oversight.
-- Completely separated from multi-turn Copilot conversations and requests.
-- Adheres strictly to MySQL 8.0+ / TiDB conventions, UTF8MB4, and datetime(6).
-- Foreign keys use ON DELETE NO ACTION to ensure historical GxP manufacturing
-- analysis records and audit trails are never cascadedly or silently erased.
-- Leaves all existing V1-V4 tables and records completely untouched.
-- ==============================================================================

CREATE TABLE `batch_analysis_reports` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `batch_number` varchar(50) NOT NULL,
  `generated_by_user_id` bigint NOT NULL,
  `client_request_id` varchar(64) NOT NULL,
  `status` enum('GENERATING','COMPLETED','FAILED') NOT NULL DEFAULT 'GENERATING',
  `model` varchar(50) DEFAULT NULL,
  `planned_batch_size` decimal(12,4) DEFAULT NULL,
  `actual_yield` decimal(12,4) DEFAULT NULL,
  `yield_percent` decimal(10,4) DEFAULT NULL,
  `yield_variance` decimal(12,4) DEFAULT NULL,
  `execution_step_count` int DEFAULT NULL,
  `qc_check_count` int DEFAULT NULL,
  `deviation_count` int DEFAULT NULL,
  `structured_analysis_json` mediumtext DEFAULT NULL,
  `report_markdown` mediumtext DEFAULT NULL,
  `sources_json` mediumtext DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_report_client_req` (`client_request_id`),
  KEY `idx_batch_report_batch_created` (`batch_id`, `created_at` DESC),
  KEY `idx_batch_report_user_created` (`generated_by_user_id`, `created_at` DESC),
  CONSTRAINT `fk_batch_report_batch` FOREIGN KEY (`batch_id`)
      REFERENCES `batches` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_batch_report_user` FOREIGN KEY (`generated_by_user_id`)
      REFERENCES `user_accounts` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
