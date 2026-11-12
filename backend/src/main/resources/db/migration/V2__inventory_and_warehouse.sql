-- ==============================================================================
-- PharmaTrack MES & eBR - Raw Material Inventory & Warehouse Management (Flyway V2)
-- ==============================================================================
-- Purely additive MySQL 8.0+ InnoDB migration introducing raw material master,
-- warehouses, storage locations, traceable material lots, and append-only stock
-- movements linked to manufacturing orders and batches.
-- Leaves all 13 V1 baseline tables and existing records completely untouched.
-- ==============================================================================

-- 1. raw_materials
CREATE TABLE `raw_materials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `description` text,
  `material_code` varchar(50) NOT NULL,
  `material_name` varchar(255) NOT NULL,
  `material_type` enum('API','EXCIPIENT','OTHER','PACKAGING','SOLVENT') NOT NULL,
  `minimum_stock_level` decimal(14,4) NOT NULL,
  `unit` varchar(50) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_raw_material_code` (`material_code`),
  KEY `idx_raw_materials_name` (`material_name`),
  KEY `idx_raw_materials_active` (`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. warehouses
CREATE TABLE `warehouses` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `location_summary` varchar(255) DEFAULT NULL,
  `storage_condition` varchar(100) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `warehouse_code` varchar(50) NOT NULL,
  `warehouse_name` varchar(150) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_warehouse_code` (`warehouse_code`),
  KEY `idx_warehouses_active` (`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. storage_locations (depends on warehouses)
CREATE TABLE `storage_locations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `aisle` varchar(40) DEFAULT NULL,
  `bin` varchar(40) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `location_code` varchar(60) NOT NULL,
  `rack` varchar(40) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `zone_name` varchar(100) DEFAULT NULL,
  `warehouse_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_location_warehouse_code` (`warehouse_id`, `location_code`),
  KEY `idx_storage_locations_active` (`active`),
  CONSTRAINT `fk_storage_locations_warehouse` FOREIGN KEY (`warehouse_id`) REFERENCES `warehouses` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. material_lots (depends on raw_materials, warehouses, storage_locations)
CREATE TABLE `material_lots` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `available_quantity` decimal(14,4) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `created_by` varchar(100) NOT NULL,
  `expiry_date` date NOT NULL,
  `initial_quantity` decimal(14,4) NOT NULL,
  `lot_number` varchar(80) NOT NULL,
  `received_date` date NOT NULL,
  `status` enum('ACTIVE','DEPLETED','EXPIRED','QUARANTINED') NOT NULL,
  `supplier_name` varchar(150) DEFAULT NULL,
  `supplier_reference` varchar(100) DEFAULT NULL,
  `unit` varchar(50) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `raw_material_id` bigint NOT NULL,
  `storage_location_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_lot_material_number` (`raw_material_id`, `lot_number`),
  KEY `idx_material_lots_warehouse_id` (`warehouse_id`),
  KEY `idx_material_lots_location_id` (`storage_location_id`),
  KEY `idx_material_lots_expiry_status` (`expiry_date`, `status`),
  CONSTRAINT `fk_material_lots_material` FOREIGN KEY (`raw_material_id`) REFERENCES `raw_materials` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_material_lots_warehouse` FOREIGN KEY (`warehouse_id`) REFERENCES `warehouses` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_material_lots_location` FOREIGN KEY (`storage_location_id`) REFERENCES `storage_locations` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. stock_movements (immutable append-only ledger; depends on material_lots, raw_materials, warehouses, storage_locations, optional orders & batches)
CREATE TABLE `stock_movements` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `movement_number` varchar(60) NOT NULL,
  `movement_type` enum('GOODS_RECEIPT','MATERIAL_ISSUE','STOCK_ADJUSTMENT') NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `performed_by` varchar(100) NOT NULL,
  `quantity_after` decimal(14,4) NOT NULL,
  `quantity_before` decimal(14,4) NOT NULL,
  `quantity_delta` decimal(14,4) NOT NULL,
  `reason` text,
  `reference_number` varchar(100) DEFAULT NULL,
  `supplier_name` varchar(150) DEFAULT NULL,
  `supplier_reference` varchar(100) DEFAULT NULL,
  `unit` varchar(50) NOT NULL,
  `batch_id` bigint DEFAULT NULL,
  `manufacturing_order_id` bigint DEFAULT NULL,
  `material_lot_id` bigint NOT NULL,
  `raw_material_id` bigint NOT NULL,
  `storage_location_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_movement_number` (`movement_number`),
  UNIQUE KEY `uk_stock_movement_reference` (`reference_number`),
  KEY `idx_stock_movements_material_id` (`raw_material_id`),
  KEY `idx_stock_movements_lot_id` (`material_lot_id`),
  KEY `idx_stock_movements_order_id` (`manufacturing_order_id`),
  KEY `idx_stock_movements_batch_id` (`batch_id`),
  KEY `idx_stock_movements_occurred_at` (`occurred_at`),
  CONSTRAINT `fk_stock_movements_material` FOREIGN KEY (`raw_material_id`) REFERENCES `raw_materials` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_stock_movements_lot` FOREIGN KEY (`material_lot_id`) REFERENCES `material_lots` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_stock_movements_warehouse` FOREIGN KEY (`warehouse_id`) REFERENCES `warehouses` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_stock_movements_location` FOREIGN KEY (`storage_location_id`) REFERENCES `storage_locations` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_stock_movements_order` FOREIGN KEY (`manufacturing_order_id`) REFERENCES `orders` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION,
  CONSTRAINT `fk_stock_movements_batch` FOREIGN KEY (`batch_id`) REFERENCES `batches` (`id`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

