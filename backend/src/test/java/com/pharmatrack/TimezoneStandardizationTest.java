package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.pharmatrack.dto.BatchQualityDossierDto;
import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.dto.OrderResponseDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchQaReview;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaAction;
import com.pharmatrack.entity.Deviation;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.exception.InvalidQualityOperationException;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchQaReviewRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.ManufacturingOrderService;
import com.pharmatrack.service.ProductionExecutionService;
import com.pharmatrack.service.QualityService;
import com.pharmatrack.util.AppTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests verifying that backend timestamps across {@link AppTime},
 * JPA entity lifecycle callbacks, and core MES/eBR services remain anchored to
 * {@code Asia/Kolkata} even when the host JVM default timezone is UTC.
 */
@ExtendWith(MockitoExtension.class)
class TimezoneStandardizationTest {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @Mock
    private ManufacturingOrderRepository orderRepository;

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private RecipeRepository recipeRepository;

    @Mock
    private BatchExecutionStepRepository executionStepRepository;

    @Mock
    private QualityCheckRepository qualityCheckRepository;

    @Mock
    private BatchQaReviewRepository batchQaReviewRepository;

    @Mock
    private DeviationRepository deviationRepository;

    @Mock
    private CapaActionRepository capaActionRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private TimeZone originalTimeZone;
    private AuditTrailService auditTrailService;
    private ManufacturingOrderService orderService;
    private ProductionExecutionService executionService;
    private QualityService qualityService;

    @BeforeEach
    void setUp() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        ObjectMapper objectMapper = new ObjectMapper();
        auditTrailService = new AuditTrailService(auditLogRepository, objectMapper);
        orderService = new ManufacturingOrderService(
                orderRepository,
                batchRepository,
                productRepository,
                recipeRepository,
                executionStepRepository,
                auditTrailService
        );
        executionService = new ProductionExecutionService(batchRepository, executionStepRepository);
        qualityService = new QualityService(
                batchRepository,
                qualityCheckRepository,
                batchQaReviewRepository,
                deviationRepository,
                capaActionRepository,
                auditTrailService
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "tz.supervisor",
                        "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_PRODUCTION_SUPERVISOR")
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TimeZone.setDefault(originalTimeZone);
    }

    private static void assertIsAsiaKolkataNow(LocalDateTime actual, LocalDateTime beforeIst, LocalDateTime afterIst, String label) {
        assertNotNull(actual, label + " should not be null");
        assertFalse(actual.isBefore(beforeIst.minusSeconds(2)),
                label + " (" + actual + ") must not be before IST lower bound (" + beforeIst.minusSeconds(2) + ")");
        assertFalse(actual.isAfter(afterIst.plusSeconds(2)),
                label + " (" + actual + ") must not be after IST upper bound (" + afterIst.plusSeconds(2) + ")");

        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        long minutesAheadOfUtc = Duration.between(utcNow, actual).toMinutes();
        assertTrue(minutesAheadOfUtc >= 325 && minutesAheadOfUtc <= 335,
                label + " should reflect IST (+05:30 = 330m ahead of UTC), but was " + minutesAheadOfUtc + "m ahead");
    }

    private static void invokeLifecycle(Object entity, String methodName) throws Exception {
        Method method = entity.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(entity);
    }

    @Test
    @DisplayName("1. AppTime anchors dates and timestamps to Asia/Kolkata under UTC JVM default")
    void appTimeAlwaysUsesAsiaKolkataEvenUnderUtcDefault() {
        assertEquals(IST_ZONE, AppTime.APP_ZONE_ID);

        LocalDateTime beforeIst = LocalDateTime.now(IST_ZONE);
        LocalDateTime appNow = AppTime.nowDateTime();
        LocalDate appToday = AppTime.nowDate();
        LocalDateTime afterIst = LocalDateTime.now(IST_ZONE);

        assertIsAsiaKolkataNow(appNow, beforeIst, afterIst, "AppTime.nowDateTime()");
        assertEquals(LocalDate.now(IST_ZONE), appToday);
    }

    @Test
    @DisplayName("2. All 10 JPA entities populate @PrePersist and @PreUpdate timestamps in Asia/Kolkata under UTC JVM default")
    void jpaLifecycleCallbacksAlwaysRecordAsiaKolkataUnderUtcDefault() throws Exception {
        LocalDateTime beforeIst = LocalDateTime.now(IST_ZONE);

        Product product = new Product();
        invokeLifecycle(product, "onCreate");
        LocalDateTime afterProductCreate = LocalDateTime.now(IST_ZONE);
        assertIsAsiaKolkataNow(product.getCreatedAt(), beforeIst, afterProductCreate, "Product.createdAt");
        assertIsAsiaKolkataNow(product.getUpdatedAt(), beforeIst, afterProductCreate, "Product.updatedAt");
        product.setUpdatedAt(beforeIst.minusHours(2));
        invokeLifecycle(product, "onUpdate");
        assertIsAsiaKolkataNow(product.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Product.updatedAt onUpdate");

        Recipe recipe = new Recipe();
        invokeLifecycle(recipe, "onCreate");
        assertIsAsiaKolkataNow(recipe.getCreatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Recipe.createdAt");
        assertIsAsiaKolkataNow(recipe.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Recipe.updatedAt");
        recipe.setUpdatedAt(beforeIst.minusHours(2));
        invokeLifecycle(recipe, "onUpdate");
        assertIsAsiaKolkataNow(recipe.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Recipe.updatedAt onUpdate");

        ManufacturingOrder order = new ManufacturingOrder();
        invokeLifecycle(order, "onCreate");
        assertIsAsiaKolkataNow(order.getCreatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "ManufacturingOrder.createdAt");
        assertIsAsiaKolkataNow(order.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "ManufacturingOrder.updatedAt");
        order.setUpdatedAt(beforeIst.minusHours(2));
        invokeLifecycle(order, "onUpdate");
        assertIsAsiaKolkataNow(order.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "ManufacturingOrder.updatedAt onUpdate");

        Batch batch = new Batch();
        invokeLifecycle(batch, "onCreate");
        assertIsAsiaKolkataNow(batch.getCreatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Batch.createdAt");
        assertIsAsiaKolkataNow(batch.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Batch.updatedAt");
        batch.setUpdatedAt(beforeIst.minusHours(2));
        invokeLifecycle(batch, "onUpdate");
        assertIsAsiaKolkataNow(batch.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Batch.updatedAt onUpdate");

        BatchExecutionStep step = new BatchExecutionStep();
        invokeLifecycle(step, "onCreate");
        assertIsAsiaKolkataNow(step.getCreatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "BatchExecutionStep.createdAt");
        assertIsAsiaKolkataNow(step.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "BatchExecutionStep.updatedAt");
        step.setUpdatedAt(beforeIst.minusHours(2));
        invokeLifecycle(step, "onUpdate");
        assertIsAsiaKolkataNow(step.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "BatchExecutionStep.updatedAt onUpdate");

        QualityCheck qc = new QualityCheck();
        invokeLifecycle(qc, "onCreate");
        assertIsAsiaKolkataNow(qc.getCreatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "QualityCheck.createdAt");
        assertIsAsiaKolkataNow(qc.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "QualityCheck.updatedAt");
        assertIsAsiaKolkataNow(qc.getCheckedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "QualityCheck.checkedAt");
        qc.setUpdatedAt(beforeIst.minusHours(2));
        invokeLifecycle(qc, "onUpdate");
        assertIsAsiaKolkataNow(qc.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "QualityCheck.updatedAt onUpdate");

        BatchQaReview qaReview = new BatchQaReview();
        invokeLifecycle(qaReview, "onCreate");
        assertIsAsiaKolkataNow(qaReview.getCreatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "BatchQaReview.createdAt");
        assertIsAsiaKolkataNow(qaReview.getReviewedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "BatchQaReview.reviewedAt");
        InvocationTargetException qaUpdateEx = assertThrows(InvocationTargetException.class,
                () -> invokeLifecycle(qaReview, "onUpdate"));
        assertInstanceOf(UnsupportedOperationException.class, qaUpdateEx.getCause());

        Deviation deviation = new Deviation();
        invokeLifecycle(deviation, "onCreate");
        assertIsAsiaKolkataNow(deviation.getCreatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Deviation.createdAt");
        assertIsAsiaKolkataNow(deviation.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Deviation.updatedAt");
        assertIsAsiaKolkataNow(deviation.getOccurredAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Deviation.occurredAt");
        assertIsAsiaKolkataNow(deviation.getReportedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Deviation.reportedAt");
        deviation.setUpdatedAt(beforeIst.minusHours(2));
        invokeLifecycle(deviation, "onUpdate");
        assertIsAsiaKolkataNow(deviation.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "Deviation.updatedAt onUpdate");

        CapaAction capa = new CapaAction();
        invokeLifecycle(capa, "onCreate");
        assertIsAsiaKolkataNow(capa.getCreatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "CapaAction.createdAt");
        assertIsAsiaKolkataNow(capa.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "CapaAction.updatedAt");
        capa.setUpdatedAt(beforeIst.minusHours(2));
        invokeLifecycle(capa, "onUpdate");
        assertIsAsiaKolkataNow(capa.getUpdatedAt(), beforeIst, LocalDateTime.now(IST_ZONE), "CapaAction.updatedAt onUpdate");

        AuditLog auditLog = new AuditLog();
        invokeLifecycle(auditLog, "onCreate");
        assertIsAsiaKolkataNow(auditLog.getOccurredAt(), beforeIst, LocalDateTime.now(IST_ZONE), "AuditLog.occurredAt");
        InvocationTargetException auditUpdateEx = assertThrows(InvocationTargetException.class,
                () -> invokeLifecycle(auditLog, "onUpdate"));
        assertInstanceOf(UnsupportedOperationException.class, auditUpdateEx.getCause());
    }

    @Test
    @DisplayName("3. QualityService QC check creation, future-time check, correction, and QA disposition use Asia/Kolkata under UTC JVM default")
    void qualityServiceTimestampsAndFutureWindowUseAsiaKolkataUnderUtcDefault() {
        Product product = new Product("TZ-PRD-1", "Timezone Tablet", "500 mg", "Tablet", null, ProductStatus.ACTIVE);
        product.setId(1L);
        Recipe recipe = new Recipe("RCP-TZ-V1", product, 1, new BigDecimal("100.0000"), "kg", RecipeStatus.APPROVED, null);
        recipe.setId(10L);
        ManufacturingOrder order = new ManufacturingOrder("MO-TZ-1", product, recipe, 1, new BigDecimal("100.0000"), "kg", 1, OrderStatus.COMPLETED, null, null, null);
        order.setId(20L);
        Batch completedBatch = new Batch("BAT-TZ-01", order, new BigDecimal("100.0000"), "kg", BatchStatus.COMPLETED, LocalDate.now(IST_ZONE), null);
        completedBatch.setId(30L);
        completedBatch.setQaStatus(BatchQualityStatus.PENDING_QA);

        when(batchRepository.findByIdWithLock(30L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.save(any(QualityCheck.class))).thenAnswer(inv -> {
            QualityCheck q = inv.getArgument(0);
            if (q.getId() == null) {
                q.setId(50L);
            }
            return q;
        });
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog log = inv.getArgument(0);
            if (log.getId() == null) {
                log.setId(1L);
            }
            return log;
        });

        // A. Null checkedAt defaults to IST (would fail by ~5h30m if JVM UTC default were used)
        LocalDateTime beforeCreateIst = LocalDateTime.now(IST_ZONE);
        QualityCheckResponseDto defaultedCheck = qualityService.createQualityCheck(
                30L,
                new QualityCheckRequestDto("Weight Variation", "490-510", "500", "mg", QualityCheckResult.PASS, true, null, null)
        );
        LocalDateTime afterCreateIst = LocalDateTime.now(IST_ZONE);
        assertIsAsiaKolkataNow(defaultedCheck.getCheckedAt(), beforeCreateIst, afterCreateIst, "QualityCheck.checkedAt default");

        // B. Timestamp representing an earlier IST time (UTC now + 2h == IST now - 3h30m) is ACCEPTED under UTC JVM default
        LocalDateTime validIstSubmittedUnderUtc = LocalDateTime.now(ZoneOffset.UTC).plusHours(2);
        QualityCheckResponseDto pastIstCheck = qualityService.createQualityCheck(
                30L,
                new QualityCheckRequestDto("Disintegration", "<= 15", "8", "min", QualityCheckResult.PASS, true, validIstSubmittedUnderUtc, null)
        );
        assertEquals(validIstSubmittedUnderUtc, pastIstCheck.getCheckedAt());

        // C. Timestamp > 5 minutes in the future in Asia/Kolkata is REJECTED with Asia/Kolkata message
        LocalDateTime futureIst = LocalDateTime.now(IST_ZONE).plusMinutes(6);
        InvalidQualityOperationException futureEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.createQualityCheck(
                        30L,
                        new QualityCheckRequestDto("Assay", "95-105", "99", "%", QualityCheckResult.PASS, true, futureIst, null)
                )
        );
        assertTrue(futureEx.getMessage().contains("Asia/Kolkata"));

        // D. Correction timestamp uses IST
        QualityCheck existingCheck = new QualityCheck(completedBatch, "Assay", "95-105", null, "%",
                QualityCheckResult.PENDING, true, "QC Analyst", beforeCreateIst.minusMinutes(10), null);
        existingCheck.setId(60L);
        when(qualityCheckRepository.findByIdWithBatch(60L)).thenReturn(Optional.of(existingCheck));

        LocalDateTime beforeCorrectIst = LocalDateTime.now(IST_ZONE);
        QualityCheckResponseDto corrected = qualityService.correctQualityCheck(
                60L,
                new QualityCheckCorrectionRequestDto("Assay", "95-105", "99.2", "%",
                        QualityCheckResult.PASS, true, "Verified", "Recorded result")
        );
        LocalDateTime afterCorrectIst = LocalDateTime.now(IST_ZONE);
        assertIsAsiaKolkataNow(corrected.getCorrectedAt(), beforeCorrectIst, afterCorrectIst, "QualityCheck.correctedAt");

        // E. QA disposition reviewedAt uses IST
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(30L)).thenReturn(List.of(existingCheck));
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchQaReviewRepository.save(any(BatchQaReview.class))).thenAnswer(inv -> {
            BatchQaReview rev = inv.getArgument(0);
            if (rev.getId() == null) {
                rev.setId(70L);
            }
            return rev;
        });
        when(batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(30L)).thenReturn(List.of());
        when(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(30L)).thenReturn(List.of());

        LocalDateTime beforeQaIst = LocalDateTime.now(IST_ZONE);
        BatchQualityDossierDto dossier = qualityService.recordQaDisposition(
                30L,
                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Hold for additional review")
        );
        LocalDateTime afterQaIst = LocalDateTime.now(IST_ZONE);
        assertNotNull(dossier);
        verify(batchQaReviewRepository).save(argThat(review -> {
            assertIsAsiaKolkataNow(review.getReviewedAt(), beforeQaIst, afterQaIst, "BatchQaReview.reviewedAt");
            return true;
        }));
    }

    @Test
    @DisplayName("4. ProductionExecutionService and AuditTrailService record Asia/Kolkata timestamps under UTC JVM default")
    void productionExecutionAndAuditTrailServiceRecordAsiaKolkataUnderUtcDefault() {
        Product product = new Product("TZ-PRD-2", "Exec Product", "250 mg", "Capsule", null, ProductStatus.ACTIVE);
        Recipe recipe = new Recipe("RCP-TZ-2-V1", product, 1, new BigDecimal("50.0000"), "kg", RecipeStatus.APPROVED, null);
        ManufacturingOrder order = new ManufacturingOrder("MO-TZ-2", product, recipe, 1, new BigDecimal("50.0000"), "kg", 1, OrderStatus.IN_PROGRESS, null, null, null);
        order.setId(21L);
        Batch batch = new Batch("BAT-TZ-02", order, new BigDecimal("50.0000"), "kg", BatchStatus.IN_PROGRESS, LocalDate.now(IST_ZONE), null);
        batch.setId(31L);
        RecipeStep recipeStep = new RecipeStep(1, "Blending", "Blend at 25 RPM");
        BatchExecutionStep execStep = new BatchExecutionStep(batch, recipeStep, 1, "Blending", "Blend at 25 RPM", ExecutionStepStatus.PENDING);
        execStep.setId(41L);

        when(batchRepository.findByIdWithLock(31L)).thenReturn(Optional.of(batch));
        when(executionStepRepository.findById(41L)).thenReturn(Optional.of(execStep));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(31L)).thenReturn(List.of(execStep));
        when(executionStepRepository.save(any(BatchExecutionStep.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime beforeStartIst = LocalDateTime.now(IST_ZONE);
        var startedDto = executionService.startExecutionStep(31L, 41L);
        LocalDateTime afterStartIst = LocalDateTime.now(IST_ZONE);
        assertIsAsiaKolkataNow(startedDto.getStartedAt(), beforeStartIst, afterStartIst, "BatchExecutionStep.startedAt");

        LocalDateTime beforeCompleteIst = LocalDateTime.now(IST_ZONE);
        var completedDto = executionService.completeExecutionStep(
                31L, 41L, new CompleteExecutionStepRequestDto("25 RPM", "Nominal"));
        LocalDateTime afterCompleteIst = LocalDateTime.now(IST_ZONE);
        assertIsAsiaKolkataNow(completedDto.getCompletedAt(), beforeCompleteIst, afterCompleteIst, "BatchExecutionStep.completedAt");

        LocalDateTime beforeAuditIst = LocalDateTime.now(IST_ZONE);
        AuditLog recorded = auditTrailService.recordAudit(
                "PRODUCT", 1L, "TZ-PRD-2", null, "PRODUCT_UPDATED", "Admin", null, "UTC test", null, null);
        LocalDateTime afterAuditIst = LocalDateTime.now(IST_ZONE);
        assertIsAsiaKolkataNow(recorded.getOccurredAt(), beforeAuditIst, afterAuditIst, "AuditLog.occurredAt fallback");
    }

    @Test
    @DisplayName("5. ManufacturingOrderService order and batch transitions record Asia/Kolkata timestamps and manufactureDate under UTC JVM default")
    void manufacturingOrderAndBatchTransitionsRecordAsiaKolkataUnderUtcDefault() {
        Product product = new Product("TZ-PRD-3", "Order TZ Product", "100 mg", "Tablet", null, ProductStatus.ACTIVE);
        product.setId(3L);
        Recipe recipe = new Recipe("RCP-TZ-3-V1", product, 1, new BigDecimal("100.0000"), "kg", RecipeStatus.APPROVED, null);
        recipe.setId(13L);
        RecipeStep step = new RecipeStep(1, "Compression", "Compress tablets");
        step.setId(301L);
        recipe.addStep(step);

        ManufacturingOrder order = new ManufacturingOrder("MO-TZ-3", product, recipe, 1, new BigDecimal("100.0000"), "kg", 1, OrderStatus.RELEASED, null, null, null);
        order.setId(22L);
        Batch batch = new Batch("BAT-TZ-03", order, new BigDecimal("100.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setId(32L);
        order.addBatch(batch);

        when(batchRepository.findById(32L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(22L)).thenReturn(Optional.of(order));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(32L)).thenReturn(List.of());
        when(executionStepRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(ManufacturingOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        // Start batch -> populates batch.startTime, batch.manufactureDate, order.actualStartDate in IST
        LocalDateTime beforeBatchStartIst = LocalDateTime.now(IST_ZONE);
        BatchResponseDto startedBatch = orderService.updateBatchStatus(
                32L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS));
        LocalDateTime afterBatchStartIst = LocalDateTime.now(IST_ZONE);

        assertIsAsiaKolkataNow(startedBatch.getStartTime(), beforeBatchStartIst, afterBatchStartIst, "Batch.startTime");
        assertIsAsiaKolkataNow(order.getActualStartDate(), beforeBatchStartIst, afterBatchStartIst, "Order.actualStartDate (batch start)");
        assertEquals(LocalDate.now(IST_ZONE), startedBatch.getManufactureDate());

        // Complete batch -> populates batch.endTime and order.actualEndDate in IST
        when(batchRepository.findByOrderId(22L)).thenReturn(List.of(batch));
        when(executionStepRepository.existsByBatchId(32L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(32L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);

        LocalDateTime beforeBatchCompleteIst = LocalDateTime.now(IST_ZONE);
        BatchResponseDto completedBatch = orderService.updateBatchStatus(
                32L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("99.5000")));
        LocalDateTime afterBatchCompleteIst = LocalDateTime.now(IST_ZONE);

        assertIsAsiaKolkataNow(completedBatch.getEndTime(), beforeBatchCompleteIst, afterBatchCompleteIst, "Batch.endTime");
        assertIsAsiaKolkataNow(order.getActualEndDate(), beforeBatchCompleteIst, afterBatchCompleteIst, "Order.actualEndDate (batch complete)");

        // Explicit order transitions also populate actualStartDate / actualEndDate in IST
        ManufacturingOrder manualOrder = new ManufacturingOrder("MO-TZ-4", product, recipe, 1, new BigDecimal("100.0000"), "kg", 1, OrderStatus.RELEASED, null, null, null);
        manualOrder.setId(23L);
        Batch completedChild = new Batch("BAT-TZ-04", manualOrder, new BigDecimal("100.0000"), "kg", BatchStatus.COMPLETED, LocalDate.now(IST_ZONE), null);
        completedChild.setId(33L);
        manualOrder.addBatch(completedChild);
        when(orderRepository.findById(23L)).thenReturn(Optional.of(manualOrder));

        LocalDateTime beforeOrderStartIst = LocalDateTime.now(IST_ZONE);
        OrderResponseDto startedOrder = orderService.updateOrderStatus(23L, new OrderStatusUpdateDto(OrderStatus.IN_PROGRESS));
        LocalDateTime afterOrderStartIst = LocalDateTime.now(IST_ZONE);
        assertIsAsiaKolkataNow(startedOrder.getActualStartDate(), beforeOrderStartIst, afterOrderStartIst, "Order.actualStartDate (manual)");

        LocalDateTime beforeOrderEndIst = LocalDateTime.now(IST_ZONE);
        OrderResponseDto completedOrder = orderService.updateOrderStatus(23L, new OrderStatusUpdateDto(OrderStatus.COMPLETED));
        LocalDateTime afterOrderEndIst = LocalDateTime.now(IST_ZONE);
        assertIsAsiaKolkataNow(completedOrder.getActualEndDate(), beforeOrderEndIst, afterOrderEndIst, "Order.actualEndDate (manual)");
    }

    private record JsonRoundTripSample(LocalDateTime occurredAt, LocalDate calendarDate) {
    }

    @Test
    @DisplayName("6. AppTime instant/offset helpers, ISO parser, and Jackson ObjectMapper remain timezone-agnostic across UTC, Asia/Kolkata, and America/New_York")
    void appTimeHelpersAndObjectMapperMaintainSingleContractAcrossTimezones() throws Exception {
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), AppTime.APP_ZONE_OFFSET);

        LocalDateTime istMidnightBoundary = LocalDateTime.of(2026, 11, 11, 0, 5, 30, 123_000_000);
        Instant expectedInstant = Instant.parse("2026-11-10T18:35:30.123Z");

        for (String zoneId : List.of("UTC", "Asia/Kolkata", "America/New_York")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

            assertTrue(AppTime.nowIsoOffsetString().endsWith("+05:30"),
                    "nowIsoOffsetString must emit +05:30 offset under " + zoneId);

            assertEquals(expectedInstant, AppTime.toInstant(istMidnightBoundary));
            assertEquals(istMidnightBoundary, AppTime.fromInstant(expectedInstant));
            assertEquals(OffsetDateTime.of(2026, 11, 11, 0, 5, 30, 123_000_000, AppTime.APP_ZONE_OFFSET),
                    AppTime.toOffsetDateTime(istMidnightBoundary));
            assertEquals("2026-11-11T00:05:30.123+05:30", AppTime.formatIsoTimestamp(istMidnightBoundary));

            // Null safety
            assertNull(AppTime.toInstant(null));
            assertNull(AppTime.fromInstant(null));
            assertNull(AppTime.toOffsetDateTime(null));
            assertNull(AppTime.formatIsoTimestamp(null));
            assertNull(AppTime.parseToAppLocalDateTime(null));
            assertNull(AppTime.parseToAppLocalDateTime("   "));

            // Equivalent wire timestamps crossing UTC midnight (2026-11-10T18:30:00Z == 2026-11-11T00:00:00+05:30)
            LocalDateTime expectedIstMidnight = LocalDateTime.of(2026, 11, 11, 0, 0, 0);
            assertEquals(expectedIstMidnight, AppTime.parseToAppLocalDateTime("2026-11-10T18:30:00Z"));
            assertEquals(expectedIstMidnight, AppTime.parseToAppLocalDateTime("2026-11-11T00:00:00+05:30"));
            assertEquals(expectedIstMidnight, AppTime.parseToAppLocalDateTime("2026-11-10T13:30:00-05:00"));
            assertEquals(expectedIstMidnight, AppTime.parseToAppLocalDateTime("2026-11-10T13:30:00-05:00[America/New_York]"));
            assertEquals(expectedIstMidnight, AppTime.parseToAppLocalDateTime("2026-11-11T00:00:00"));
            assertEquals(expectedIstMidnight, AppTime.parseToAppLocalDateTime("2026-11-11T00:00"));

            assertThrows(DateTimeParseException.class,
                    () -> AppTime.parseToAppLocalDateTime("not-a-timestamp"));

            // ObjectMapper round-trip for LocalDateTime (+05:30) and calendar LocalDate (DST boundary safe)
            ObjectMapper mapper = AppTime.createObjectMapper();
            LocalDate dstSpring = LocalDate.of(2026, 3, 8);
            String serialized = mapper.writeValueAsString(new JsonRoundTripSample(istMidnightBoundary, dstSpring));
            assertTrue(serialized.contains("\"occurredAt\":\"2026-11-11T00:05:30.123+05:30\""),
                    "Serialized timestamp must include +05:30: " + serialized);
            assertTrue(serialized.contains("\"calendarDate\":\"2026-03-08\""),
                    "Calendar date must stay YYYY-MM-DD: " + serialized);

            JsonRoundTripSample fromZ = mapper.readValue(
                    "{\"occurredAt\":\"2026-11-10T18:35:30.123Z\",\"calendarDate\":\"2026-11-01\"}",
                    JsonRoundTripSample.class);
            assertEquals(istMidnightBoundary, fromZ.occurredAt());
            assertEquals(LocalDate.of(2026, 11, 1), fromZ.calendarDate());

            JsonRoundTripSample fromNy = mapper.readValue(
                    "{\"occurredAt\":\"2026-11-10T13:35:30.123-05:00\",\"calendarDate\":\"2024-02-29\"}",
                    JsonRoundTripSample.class);
            assertEquals(istMidnightBoundary, fromNy.occurredAt());
            assertEquals(LocalDate.of(2024, 2, 29), fromNy.calendarDate());

            JsonRoundTripSample fromOffsetFree = mapper.readValue(
                    "{\"occurredAt\":\"2026-11-11T00:05:30.123\",\"calendarDate\":\"2026-11-11\"}",
                    JsonRoundTripSample.class);
            assertEquals(istMidnightBoundary, fromOffsetFree.occurredAt());

            JsonRoundTripSample fromArray = mapper.readValue(
                    "{\"occurredAt\":[2026,11,11,0,5,30],\"calendarDate\":\"2026-11-11\"}",
                    JsonRoundTripSample.class);
            assertEquals(LocalDateTime.of(2026, 11, 11, 0, 5, 30), fromArray.occurredAt());

            assertThrows(InvalidFormatException.class, () ->
                    mapper.readValue("{\"occurredAt\":\"invalid-ts\"}", JsonRoundTripSample.class));
        }
    }
}
