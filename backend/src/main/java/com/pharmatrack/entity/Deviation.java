package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JPA entity representing a manufacturing or quality {@code Deviation} record
 * linked to an affected {@link Batch} and optionally to a {@link QualityCheck},
 * {@link BatchExecutionStep}, or {@link AuditLog} entry.
 */
@Entity
@Table(
        name = "deviations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_deviation_number", columnNames = "deviation_number")
        },
        indexes = {
                @Index(name = "idx_deviations_batch_id", columnList = "batch_id"),
                @Index(name = "idx_deviations_status", columnList = "status"),
                @Index(name = "idx_deviations_quality_check_id", columnList = "quality_check_id"),
                @Index(name = "idx_deviations_execution_step_id", columnList = "execution_step_id"),
                @Index(name = "idx_deviations_source_audit_log_id", columnList = "source_audit_log_id")
        }
)
public class Deviation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Deviation number is mandatory")
    @Size(max = 50, message = "Deviation number must not exceed 50 characters")
    @Column(name = "deviation_number", nullable = false, unique = true, length = 50)
    private String deviationNumber;

    @NotNull(message = "Affected batch reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private Batch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_check_id")
    private QualityCheck qualityCheck;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_step_id")
    private BatchExecutionStep executionStep;

    @Column(name = "source_audit_log_id")
    private Long sourceAuditLogId;

    @NotBlank(message = "Deviation title is mandatory")
    @Size(max = 200, message = "Deviation title must not exceed 200 characters")
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @NotNull(message = "Deviation category is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 40)
    private DeviationCategory category;

    @NotNull(message = "Deviation severity is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private DeviationSeverity severity;

    @NotNull(message = "Deviation priority is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private DeviationPriority priority;

    @NotNull(message = "Deviation status is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DeviationStatus status;

    @NotBlank(message = "Deviation description is mandatory")
    @Size(max = 3000, message = "Deviation description must not exceed 3000 characters")
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Size(max = 2000, message = "Immediate containment action must not exceed 2000 characters")
    @Column(name = "immediate_containment", columnDefinition = "TEXT")
    private String immediateContainment;

    @NotBlank(message = "Reporter name is mandatory")
    @Size(max = 100, message = "Reporter name must not exceed 100 characters")
    @Column(name = "reported_by", nullable = false, length = 100)
    private String reportedBy;

    @NotNull(message = "Occurrence timestamp is mandatory")
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @NotNull(message = "Reported timestamp is mandatory")
    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    @Size(max = 3000, message = "Investigation summary must not exceed 3000 characters")
    @Column(name = "investigation_summary", columnDefinition = "TEXT")
    private String investigationSummary;

    @Size(max = 100, message = "Investigated by must not exceed 100 characters")
    @Column(name = "investigated_by", length = 100)
    private String investigatedBy;

    @Column(name = "investigation_started_at")
    private LocalDateTime investigationStartedAt;

    @Size(max = 100, message = "Root cause category must not exceed 100 characters")
    @Column(name = "root_cause_category", length = 100)
    private String rootCauseCategory;

    @Size(max = 3000, message = "Root cause analysis must not exceed 3000 characters")
    @Column(name = "root_cause_analysis", columnDefinition = "TEXT")
    private String rootCauseAnalysis;

    @Size(max = 2000, message = "Impact assessment must not exceed 2000 characters")
    @Column(name = "impact_assessment", columnDefinition = "TEXT")
    private String impactAssessment;

    @Size(max = 100, message = "Root cause identified by must not exceed 100 characters")
    @Column(name = "root_cause_identified_by", length = 100)
    private String rootCauseIdentifiedBy;

    @Column(name = "root_cause_identified_at")
    private LocalDateTime rootCauseIdentifiedAt;

    @Size(max = 2000, message = "CAPA plan summary must not exceed 2000 characters")
    @Column(name = "capa_plan_summary", columnDefinition = "TEXT")
    private String capaPlanSummary;

    @Size(max = 100, message = "Verified by must not exceed 100 characters")
    @Column(name = "verified_by", length = 100)
    private String verifiedBy;

    @Size(max = 2000, message = "Verification notes must not exceed 2000 characters")
    @Column(name = "verification_notes", columnDefinition = "TEXT")
    private String verificationNotes;

    @Size(max = 100, message = "Closed by must not exceed 100 characters")
    @Column(name = "closed_by", length = 100)
    private String closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @OneToMany(mappedBy = "deviation", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("id ASC")
    private List<CapaAction> capaActions = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Deviation() {
    }

    public Deviation(String deviationNumber, Batch batch, QualityCheck qualityCheck,
                     BatchExecutionStep executionStep, Long sourceAuditLogId,
                     String title, DeviationCategory category, DeviationSeverity severity,
                     DeviationPriority priority, DeviationStatus status, String description,
                     String immediateContainment, String reportedBy,
                     LocalDateTime occurredAt, LocalDateTime reportedAt) {
        this.deviationNumber = deviationNumber;
        this.batch = batch;
        this.qualityCheck = qualityCheck;
        this.executionStep = executionStep;
        this.sourceAuditLogId = sourceAuditLogId;
        this.title = title;
        this.category = category;
        this.severity = severity;
        this.priority = priority;
        this.status = status != null ? status : DeviationStatus.OPEN;
        this.description = description;
        this.immediateContainment = immediateContainment;
        this.reportedBy = reportedBy;
        this.occurredAt = occurredAt;
        this.reportedAt = reportedAt;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = DeviationStatus.OPEN;
        }
        if (this.occurredAt == null) {
            this.occurredAt = now;
        }
        if (this.reportedAt == null) {
            this.reportedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = AppTime.nowDateTime();
    }

    // Bidirectional helper methods (intentionally no removal helper to preserve CAPA history)

    public void addCapaAction(CapaAction capaAction) {
        if (capaAction != null) {
            this.capaActions.add(capaAction);
            capaAction.setDeviation(this);
        }
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviationNumber() {
        return deviationNumber;
    }

    public void setDeviationNumber(String deviationNumber) {
        this.deviationNumber = deviationNumber;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    public QualityCheck getQualityCheck() {
        return qualityCheck;
    }

    public void setQualityCheck(QualityCheck qualityCheck) {
        this.qualityCheck = qualityCheck;
    }

    public BatchExecutionStep getExecutionStep() {
        return executionStep;
    }

    public void setExecutionStep(BatchExecutionStep executionStep) {
        this.executionStep = executionStep;
    }

    public Long getSourceAuditLogId() {
        return sourceAuditLogId;
    }

    public void setSourceAuditLogId(Long sourceAuditLogId) {
        this.sourceAuditLogId = sourceAuditLogId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public DeviationCategory getCategory() {
        return category;
    }

    public void setCategory(DeviationCategory category) {
        this.category = category;
    }

    public DeviationSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(DeviationSeverity severity) {
        this.severity = severity;
    }

    public DeviationPriority getPriority() {
        return priority;
    }

    public void setPriority(DeviationPriority priority) {
        this.priority = priority;
    }

    public DeviationStatus getStatus() {
        return status;
    }

    public void setStatus(DeviationStatus status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImmediateContainment() {
        return immediateContainment;
    }

    public void setImmediateContainment(String immediateContainment) {
        this.immediateContainment = immediateContainment;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(String reportedBy) {
        this.reportedBy = reportedBy;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(LocalDateTime reportedAt) {
        this.reportedAt = reportedAt;
    }

    public String getInvestigationSummary() {
        return investigationSummary;
    }

    public void setInvestigationSummary(String investigationSummary) {
        this.investigationSummary = investigationSummary;
    }

    public String getInvestigatedBy() {
        return investigatedBy;
    }

    public void setInvestigatedBy(String investigatedBy) {
        this.investigatedBy = investigatedBy;
    }

    public LocalDateTime getInvestigationStartedAt() {
        return investigationStartedAt;
    }

    public void setInvestigationStartedAt(LocalDateTime investigationStartedAt) {
        this.investigationStartedAt = investigationStartedAt;
    }

    public String getRootCauseCategory() {
        return rootCauseCategory;
    }

    public void setRootCauseCategory(String rootCauseCategory) {
        this.rootCauseCategory = rootCauseCategory;
    }

    public String getRootCauseAnalysis() {
        return rootCauseAnalysis;
    }

    public void setRootCauseAnalysis(String rootCauseAnalysis) {
        this.rootCauseAnalysis = rootCauseAnalysis;
    }

    public String getImpactAssessment() {
        return impactAssessment;
    }

    public void setImpactAssessment(String impactAssessment) {
        this.impactAssessment = impactAssessment;
    }

    public String getRootCauseIdentifiedBy() {
        return rootCauseIdentifiedBy;
    }

    public void setRootCauseIdentifiedBy(String rootCauseIdentifiedBy) {
        this.rootCauseIdentifiedBy = rootCauseIdentifiedBy;
    }

    public LocalDateTime getRootCauseIdentifiedAt() {
        return rootCauseIdentifiedAt;
    }

    public void setRootCauseIdentifiedAt(LocalDateTime rootCauseIdentifiedAt) {
        this.rootCauseIdentifiedAt = rootCauseIdentifiedAt;
    }

    public String getCapaPlanSummary() {
        return capaPlanSummary;
    }

    public void setCapaPlanSummary(String capaPlanSummary) {
        this.capaPlanSummary = capaPlanSummary;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(String verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public String getVerificationNotes() {
        return verificationNotes;
    }

    public void setVerificationNotes(String verificationNotes) {
        this.verificationNotes = verificationNotes;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(String closedBy) {
        this.closedBy = closedBy;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public List<CapaAction> getCapaActions() {
        return capaActions;
    }

    public void setCapaActions(List<CapaAction> capaActions) {
        this.capaActions = capaActions != null ? capaActions : new ArrayList<>();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Deviation)) return false;
        Deviation other = (Deviation) o;
        String thisNumber = getDeviationNumber();
        String otherNumber = other.getDeviationNumber();
        if (thisNumber == null || otherNumber == null) {
            return false;
        }
        return Objects.equals(thisNumber, otherNumber);
    }

    @Override
    public int hashCode() {
        return Deviation.class.hashCode();
    }

    @Override
    public String toString() {
        return "Deviation{" +
                "id=" + id +
                ", deviationNumber='" + deviationNumber + '\'' +
                ", title='" + title + '\'' +
                ", category=" + category +
                ", severity=" + severity +
                ", priority=" + priority +
                ", status=" + status +
                ", reportedBy='" + reportedBy + '\'' +
                ", reportedAt=" + reportedAt +
                '}';
    }
}

