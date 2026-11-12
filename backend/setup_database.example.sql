-- ==============================================================================
-- PharmaTrack Database & Application User Setup Example (MySQL 8.0+)
-- ==============================================================================
-- Copy to a local gitignored file (e.g., setup_database.sql) and replace ALL
-- placeholders before running with an administrative MySQL account.
-- NEVER commit setup_database.sql or real credentials to Git.
-- ==============================================================================

-- 1. Create the application schema (safe / non-destructive)
CREATE DATABASE IF NOT EXISTS pharmatrack_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- 2. Create a dedicated least-privilege application user (never use root for app runtime)
CREATE USER IF NOT EXISTS '<REPLACE_WITH_DB_USERNAME>'@'localhost'
  IDENTIFIED BY '<REPLACE_WITH_STRONG_DB_PASSWORD>';

-- 3. Grant scoped schema privileges on pharmatrack_db only
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON pharmatrack_db.*
  TO '<REPLACE_WITH_DB_USERNAME>'@'localhost';

-- 4. Apply privilege changes
FLUSH PRIVILEGES;

