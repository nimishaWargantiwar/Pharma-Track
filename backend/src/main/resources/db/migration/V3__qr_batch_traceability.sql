-- ==============================================================================
-- PharmaTrack MES & eBR - QR-Based Batch Traceability (Flyway V3)
-- ==============================================================================
-- Additive migration introducing stable QR code identifiers for production batches.
-- Leaves all existing tables, constraints, and demo records completely intact.
-- ==============================================================================

-- 1. Add qr_code column to batches
ALTER TABLE `batches`
  ADD COLUMN `qr_code` VARCHAR(100) NULL AFTER `batch_number`;

-- 2. Backfill existing batches with stable deterministic QR codes
UPDATE `batches`
  SET `qr_code` = CONCAT('QR-PT-', `batch_number`)
  WHERE `qr_code` IS NULL;

-- 3. Add unique constraint and index on qr_code
ALTER TABLE `batches`
  ADD CONSTRAINT `uk_batches_qr_code` UNIQUE (`qr_code`);

