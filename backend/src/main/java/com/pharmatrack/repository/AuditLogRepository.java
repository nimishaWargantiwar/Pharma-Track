package com.pharmatrack.repository;

import com.pharmatrack.entity.AuditLog;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Append-only repository for {@link AuditLog} entries.
 * Intentionally extends {@link Repository} (instead of CrudRepository/JpaRepository)
 * so delete and bulk-mutation operations are not exposed.
 */
@org.springframework.stereotype.Repository
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog auditLog);

    Optional<AuditLog> findById(Long id);

    List<AuditLog> findAllByOrderByOccurredAtDescIdDesc();

    List<AuditLog> findByBatchIdOrderByOccurredAtDescIdDesc(Long batchId);

    List<AuditLog> findByEntityTypeOrderByOccurredAtDescIdDesc(String entityType);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(String entityType, Long entityId);

    long count();
}
