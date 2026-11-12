-- ==============================================================================
-- PharmaTrack MES & eBR - Initial MySQL 8.0+ Schema Baseline (Flyway V1)
-- ==============================================================================
-- Exact baseline matching the 13 production-validated InnoDB tables and all
-- JPA entity mappings, constraints, indexes, checks, BIT(1), and ENUM types.
-- Ordered topologically from parent tables to child tables so execution succeeds
-- cleanly with FOREIGN_KEY_CHECKS=1 and without CREATE DATABASE/USE statements.
-- ==============================================================================

-- 1. user_accounts (no foreign keys)
CREATE TABLE `user_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `full_name` varchar(100) NOT NULL,
  `password_hash` varchar(120) NOT NULL,
  `role` enum('ADMIN','OPERATOR','PRODUCTION_SUPERVISOR','QA_REVIEWER','QC_ANALYST') NOT NULL,
  `token_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `username` varchar(50) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_accounts_username` (`username`),
  KEY `idx_user_accounts_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. products (no foreign keys)
CREATE TABLE `products` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `description` text,
  `dosage_form` varchar(100) NOT NULL,
  `product_code` varchar(50) NOT NULL,
  `product_name` varchar(255) NOT NULL,
  `status` enum('ACTIVE','INACTIVE') NOT NULL,
  `strength` varchar(100) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_code` (`product_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. audit_logs (no foreign keys; batch_id intentionally scalar/unconstrained)
CREATE TABLE `audit_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(60) NOT NULL,
  `actor_name` varchar(100) NOT NULL,
  `after_values` text,
  `batch_id` bigint DEFAULT NULL,
  `before_values` text,
  `entity_id` bigint NOT NULL,
  `entity_reference` varchar(100) DEFAULT NULL,
  `entity_type` varchar(60) NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `reason` text,
  PRIMARY KEY (`id`),
  KEY `idx_audit_logs_entity` (`entity_type`, `entity_id`),
  KEY `idx_audit_logs_batch_id` (`batch_id`),
  KEY `idx_audit_logs_occurred_at` (`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. recipes (depends on products; uk_recipe_product_version covers leading product_id)
CREATE TABLE `recipes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_size` decimal(12,4) NOT NULL,
  `batch_unit` varchar(50) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `description` text,
  `recipe_code` varchar(50) NOT NULL,
  `status` enum('APPROVED','DRAFT','OBSOLETE') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` int NOT NULL,
  `product_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe_code` (`recipe_code`),
  UNIQUE KEY `uk_recipe_product_version` (`product_id`, `version`),
  CONSTRAINT `fk_recipes_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `recipes_chk_1` CHECK ((`version` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. recipe_materials (depends on recipes)
CREATE TABLE `recipe_materials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_name` varchar(255) NOT NULL,
  `quantity` decimal(12,4) NOT NULL,
  `unit` varchar(50) NOT NULL,
  `recipe_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_recipe_materials_recipe_id` (`recipe_id`),
  CONSTRAINT `fk_recipe_materials_recipe` FOREIGN KEY (`recipe_id`) REFERENCES `recipes` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. recipe_steps (depends on recipes; uk_recipe_step covers leading recipe_id)
CREATE TABLE `recipe_steps` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `instructions` text NOT NULL,
  `operation_name` varchar(255) NOT NULL,
  `step_number` int NOT NULL,
  `recipe_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recipe_step` (`recipe_id`, `step_number`),
  CONSTRAINT `fk_recipe_steps_recipe` FOREIGN KEY (`recipe_id`) REFERENCES `recipes` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `recipe_steps_chk_1` CHECK ((`step_number` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. orders (depends on products, recipes)
CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actual_end_date` datetime(6) DEFAULT NULL,
  `actual_start_date` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `notes` text,
  `order_number` varchar(50) NOT NULL,
  `planned_end_date` date DEFAULT NULL,
  `planned_quantity` decimal(14,4) NOT NULL,
  `planned_start_date` date DEFAULT NULL,
  `quantity_unit` varchar(50) NOT NULL,
  `recipe_version` int NOT NULL,
  `status` enum('CANCELLED','COMPLETED','IN_PROGRESS','PLANNED','RELEASED') NOT NULL,
  `target_batch_count` int NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `product_id` bigint NOT NULL,
  `recipe_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_number` (`order_number`),
  KEY `idx_orders_product_id` (`product_id`),
  KEY `idx_orders_recipe_id` (`recipe_id`),
  CONSTRAINT `fk_orders_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_orders_recipe` FOREIGN KEY (`recipe_id`) REFERENCES `recipes` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `orders_chk_1` CHECK ((`recipe_version` >= 1)),
  CONSTRAINT `orders_chk_2` CHECK ((`target_batch_count` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. batches (depends on orders)
CREATE TABLE `batches` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actual_yield` decimal(12,4) DEFAULT NULL,
  `batch_number` varchar(50) NOT NULL,
  `batch_size` decimal(12,4) NOT NULL,
  `batch_unit` varchar(50) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `end_time` datetime(6) DEFAULT NULL,
  `expiry_date` date DEFAULT NULL,
  `manufacture_date` date DEFAULT NULL,
  `start_time` datetime(6) DEFAULT NULL,
  `status` enum('CANCELLED','COMPLETED','IN_PROGRESS','PLANNED') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `order_id` bigint NOT NULL,
  `qa_status` enum('ON_HOLD','PENDING_QA','REJECTED','RELEASED') DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_number` (`batch_number`),
  KEY `idx_batches_order_id` (`order_id`),
  CONSTRAINT `fk_batches_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. batch_execution_steps (depends on batches, recipe_steps; uk_batch_execution_step covers leading batch_id)
CREATE TABLE `batch_execution_steps` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actual_value` varchar(255) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `executed_by` varchar(100) DEFAULT NULL,
  `instructions` text NOT NULL,
  `observations` text,
  `operation_name` varchar(255) NOT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `status` enum('COMPLETED','IN_PROGRESS','PENDING') NOT NULL,
  `step_number` int NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `batch_id` bigint NOT NULL,
  `recipe_step_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_execution_step` (`batch_id`, `step_number`),
  KEY `idx_batch_execution_steps_recipe_step_id` (`recipe_step_id`),
  CONSTRAINT `fk_batch_execution_steps_batch` FOREIGN KEY (`batch_id`) REFERENCES `batches` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_batch_execution_steps_recipe_step` FOREIGN KEY (`recipe_step_id`) REFERENCES `recipe_steps` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `batch_execution_steps_chk_1` CHECK ((`step_number` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. quality_checks (depends on batches)
CREATE TABLE `quality_checks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `checked_at` datetime(6) NOT NULL,
  `corrected_at` datetime(6) DEFAULT NULL,
  `corrected_by` varchar(100) DEFAULT NULL,
  `correction_count` int NOT NULL,
  `correction_reason` text,
  `created_at` datetime(6) NOT NULL,
  `inspector_name` varchar(100) NOT NULL,
  `measured_value` varchar(150) DEFAULT NULL,
  `remarks` text,
  `is_required` bit(1) NOT NULL,
  `result` enum('FAIL','PASS','PENDING') NOT NULL,
  `specification` varchar(255) NOT NULL,
  `test_name` varchar(150) NOT NULL,
  `unit` varchar(50) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `batch_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_quality_checks_batch_id` (`batch_id`),
  CONSTRAINT `fk_quality_checks_batch` FOREIGN KEY (`batch_id`) REFERENCES `batches` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. batch_qa_reviews (depends on batches)
CREATE TABLE `batch_qa_reviews` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `decision_status` enum('ON_HOLD','PENDING_QA','REJECTED','RELEASED') NOT NULL,
  `failed_checks_count` bigint NOT NULL,
  `passed_checks_count` bigint NOT NULL,
  `pending_checks_count` bigint NOT NULL,
  `previous_status` enum('ON_HOLD','PENDING_QA','REJECTED','RELEASED') DEFAULT NULL,
  `rationale` text NOT NULL,
  `reviewed_at` datetime(6) NOT NULL,
  `reviewer_name` varchar(100) NOT NULL,
  `total_checks_count` bigint NOT NULL,
  `batch_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_batch_qa_reviews_batch_id` (`batch_id`),
  CONSTRAINT `fk_batch_qa_reviews_batch` FOREIGN KEY (`batch_id`) REFERENCES `batches` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. deviations (depends on batches, batch_execution_steps, quality_checks; source_audit_log_id intentionally unconstrained)
CREATE TABLE `deviations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `capa_plan_summary` text,
  `category` enum('DOCUMENTATION_ERROR','ENVIRONMENTAL_EXCURSION','EQUIPMENT_MALFUNCTION','IN_PROCESS_CONTROL','MATERIAL_ISSUE','OTHER','PROCESS_PARAMETER','QC_OOS_FAILURE') NOT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `closed_by` varchar(100) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `description` text NOT NULL,
  `deviation_number` varchar(50) NOT NULL,
  `immediate_containment` text,
  `impact_assessment` text,
  `investigated_by` varchar(100) DEFAULT NULL,
  `investigation_started_at` datetime(6) DEFAULT NULL,
  `investigation_summary` text,
  `occurred_at` datetime(6) NOT NULL,
  `priority` enum('HIGH','LOW','MEDIUM') NOT NULL,
  `reported_at` datetime(6) NOT NULL,
  `reported_by` varchar(100) NOT NULL,
  `root_cause_analysis` text,
  `root_cause_category` varchar(100) DEFAULT NULL,
  `root_cause_identified_at` datetime(6) DEFAULT NULL,
  `root_cause_identified_by` varchar(100) DEFAULT NULL,
  `severity` enum('CRITICAL','MAJOR','MINOR') NOT NULL,
  `source_audit_log_id` bigint DEFAULT NULL,
  `status` enum('CAPA_IN_PROGRESS','CLOSED','OPEN','PENDING_VERIFICATION','ROOT_CAUSE_IDENTIFIED','UNDER_INVESTIGATION') NOT NULL,
  `title` varchar(200) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `verification_notes` text,
  `verified_by` varchar(100) DEFAULT NULL,
  `batch_id` bigint NOT NULL,
  `execution_step_id` bigint DEFAULT NULL,
  `quality_check_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_deviation_number` (`deviation_number`),
  KEY `idx_deviations_batch_id` (`batch_id`),
  KEY `idx_deviations_status` (`status`),
  KEY `idx_deviations_quality_check_id` (`quality_check_id`),
  KEY `idx_deviations_execution_step_id` (`execution_step_id`),
  KEY `idx_deviations_source_audit_log_id` (`source_audit_log_id`),
  CONSTRAINT `fk_deviations_batch` FOREIGN KEY (`batch_id`) REFERENCES `batches` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_deviations_execution_step` FOREIGN KEY (`execution_step_id`) REFERENCES `batch_execution_steps` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_deviations_quality_check` FOREIGN KEY (`quality_check_id`) REFERENCES `quality_checks` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 13. capa_actions (depends on deviations)
CREATE TABLE `capa_actions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action_plan` text NOT NULL,
  `cancellation_reason` text,
  `capa_number` varchar(50) NOT NULL,
  `capa_type` enum('CORRECTIVE','PREVENTIVE') NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `completed_by` varchar(100) DEFAULT NULL,
  `completion_evidence` text,
  `created_at` datetime(6) NOT NULL,
  `created_by` varchar(100) NOT NULL,
  `due_date` date NOT NULL,
  `owner_name` varchar(100) NOT NULL,
  `status` enum('CANCELLED','COMPLETED','IN_PROGRESS','OPEN') NOT NULL,
  `title` varchar(200) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `deviation_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_capa_number` (`capa_number`),
  KEY `idx_capa_actions_deviation_id` (`deviation_id`),
  KEY `idx_capa_actions_status_due` (`status`, `due_date`),
  CONSTRAINT `fk_capa_actions_deviation` FOREIGN KEY (`deviation_id`) REFERENCES `deviations` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

