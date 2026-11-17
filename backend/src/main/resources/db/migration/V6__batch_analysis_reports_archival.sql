-- ==============================================================================
-- PharmaTrack MES & eBR - AI Batch Analysis Reports Archival (Flyway V6)
-- ==============================================================================
-- Additive migration introducing soft-archival metadata for AI Batch Analysis
-- Reports to remove unwanted reports from active operational views while strictly
-- preserving the full GxP audit trail and historical report immutability.
-- Compatible with MySQL 8.0+, TiDB, and H2.
-- ==============================================================================

ALTER TABLE `batch_analysis_reports`
  ADD COLUMN `archived` boolean NOT NULL DEFAULT FALSE;

ALTER TABLE `batch_analysis_reports`
  ADD COLUMN `archived_at` datetime(6) DEFAULT NULL;

ALTER TABLE `batch_analysis_reports`
  ADD COLUMN `archived_by_user_id` bigint DEFAULT NULL;

ALTER TABLE `batch_analysis_reports`
  ADD COLUMN `archive_reason` varchar(500) DEFAULT NULL;

ALTER TABLE `batch_analysis_reports`
  ADD CONSTRAINT `fk_batch_report_archived_user` FOREIGN KEY (`archived_by_user_id`)
      REFERENCES `user_accounts` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION;

CREATE INDEX `idx_batch_report_archived` ON `batch_analysis_reports` (`batch_id`, `archived`, `created_at` DESC);
