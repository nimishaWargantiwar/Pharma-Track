-- ==============================================================================
-- PharmaTrack MES & eBR - AI Batch Analysis Modes and Focus Areas (Flyway V7)
-- ==============================================================================
-- Additive migration introducing analysis modes (FULL vs. FOCUSED) and focus area
-- tracking for AI Batch Analysis Reports.
-- Ensures existing historical reports (#1-#5) remain strictly intact and default to FULL.
-- Compatible with MySQL 8.0+, TiDB, and H2.
-- ==============================================================================

ALTER TABLE `batch_analysis_reports`
  ADD COLUMN `analysis_mode` varchar(30) NOT NULL DEFAULT 'FULL';

ALTER TABLE `batch_analysis_reports`
  ADD COLUMN `focus_areas_json` varchar(1000) DEFAULT NULL;

ALTER TABLE `batch_analysis_reports`
  ADD COLUMN `focus_summary` varchar(255) DEFAULT NULL;

CREATE INDEX `idx_batch_report_mode` ON `batch_analysis_reports` (`batch_id`, `analysis_mode`, `created_at` DESC);
