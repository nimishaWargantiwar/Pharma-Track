-- ==============================================================================
-- PharmaTrack MES & eBR - AI Copilot Conversation Persistence (Flyway V4)
-- ==============================================================================
-- Additive InnoDB migration introducing persistent multi-turn AI Copilot
-- conversation threads, idempotent request tracking, and grounded message transcripts.
-- Adheres strictly to MySQL 8.0+ / TiDB conventions, UTF8MB4, and datetime(6).
-- Leaves all existing V1-V3 tables and records completely untouched.
-- ==============================================================================

-- 1. copilot_conversations (Master thread)
CREATE TABLE `copilot_conversations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `title` varchar(150) NOT NULL,
  `active_module` varchar(50) DEFAULT NULL,
  `context_type` varchar(50) DEFAULT NULL,
  `context_id` varchar(100) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_copilot_conv_user_updated` (`user_id`, `updated_at` DESC),
  CONSTRAINT `fk_copilot_conv_user` FOREIGN KEY (`user_id`)
      REFERENCES `user_accounts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. copilot_requests (Idempotency, request hash, leasing, and status lifecycle)
CREATE TABLE `copilot_requests` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `client_request_id` varchar(64) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `status` enum('PENDING','COMPLETED','FAILED') NOT NULL DEFAULT 'PENDING',
  `lease_token` varchar(64) DEFAULT NULL,
  `started_at` datetime(6) NOT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_copilot_req_client` (`conversation_id`, `client_request_id`),
  KEY `idx_copilot_req_conv_started` (`conversation_id`, `started_at` ASC),
  KEY `idx_copilot_req_status_expires` (`status`, `lease_expires_at`),
  CONSTRAINT `fk_copilot_req_conv` FOREIGN KEY (`conversation_id`)
      REFERENCES `copilot_conversations` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. copilot_messages (Normalized transcript: exactly 1 USER and 1 ASSISTANT per request)
CREATE TABLE `copilot_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` bigint NOT NULL,
  `role` enum('USER','ASSISTANT') NOT NULL,
  `content` text NOT NULL,
  `model` varchar(50) DEFAULT NULL,
  `context_type` varchar(50) DEFAULT NULL,
  `context_identifier` varchar(100) DEFAULT NULL,
  `sources_json` text DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_copilot_msg_request_role` (`request_id`, `role`),
  CONSTRAINT `fk_copilot_msg_req` FOREIGN KEY (`request_id`)
      REFERENCES `copilot_requests` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
