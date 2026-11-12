package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaAction;
import com.pharmatrack.entity.CapaStatus;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.Deviation;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.JwtService;
import com.pharmatrack.util.AppTime;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end persistence, Hibernate schema-validation compatibility, and HTTP JSON
 * wire-contract tests proving that:
 * <ul>
 *   <li>All {@link LocalDateTime} attributes persist identical UTC instants in SQL columns
 *       and hydrate back to identical {@code Asia/Kolkata} values across {@code Asia/Kolkata},
 *       {@code UTC}, and {@code America/New_York} JVMs.</li>
 *   <li>All {@link LocalDate} business calendar dates remain unshifted across DST boundaries.</li>
 *   <li>{@link com.pharmatrack.entity.AsiaKolkataLocalDateTimeConverter} preserves MySQL {@code DATETIME(6)}
 *       DDL/validation compatibility for {@code ddl-auto=validate}.</li>
 *   <li>All JSON HTTP responses emit explicit {@code +05:30} offsets and HTTP inputs accept
 *       {@code Z}, {@code +05:30}, {@code -05:00}/{@code -04:00}, and offset-free IST ISO strings.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_tz_contract_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TimezonePersistenceAndJsonIntegrationTest {

    private static final Pattern IST_OFFSET_ISO_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\+05:30$");
    private static final List<String> TEST_ZONES = List.of("Asia/Kolkata", "UTC", "America/New_York");
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private ManufacturingOrderRepository orderRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private QualityCheckRepository qualityCheckRepository;

    @Autowired
    private DeviationRepository deviationRepository;

    @Autowired
    private CapaActionRepository capaActionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private TimeZone originalTimeZone;

    @BeforeEach
    void captureDefaultTimezone() {
        originalTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreDefaultTimezone() {
        TimeZone.setDefault(originalTimeZone);
    }

    private String bearerFor(String username, Role role) {
        String normalized = UserAccount.normalizeUsername(username);
        UserAccount user = userAccountRepository.findByUsernameIgnoreCase(normalized)
                .orElseGet(() -> userAccountRepository.saveAndFlush(new UserAccount(
                        normalized,
                        passwordEncoder.encode("TestPass#" + UUID.randomUUID()),
                        username.trim(),
                        role,
                        true
                )));
        assertEquals(role, user.getRole());
        return "Bearer " + jwtService.generateToken(user);
    }

    @Test
    @DisplayName("1. JPA writes identical UTC SQL DATETIME values and reads identical Asia/Kolkata timestamps and DST-safe calendar dates across IST, UTC, and New York JVMs")
    void jpaPersistenceMaintainsUtcStorageAndIstDomainAcrossJvmTimezones() {
        LocalDateTime istBoundary = LocalDateTime.of(2026, 11, 11, 0, 5, 0);
        Instant expectedUtcInstant = Instant.parse("2026-11-10T18:35:00Z");

        LocalDate dstSpringDate = LocalDate.of(2026, 3, 8);
        LocalDate dstFallDate = LocalDate.of(2026, 11, 1);
        LocalDate leapDate = LocalDate.of(2024, 2, 29);

        for (String writerZone : TEST_ZONES) {
            TimeZone.setDefault(TimeZone.getTimeZone(writerZone));
            int n = SEQ.getAndIncrement();

            Long[] ids = transactionTemplate.execute(status -> {
                Product product = productRepository.saveAndFlush(new Product(
                        "TZ-PRD-" + n,
                        "TZ Persistence Product " + n,
                        "500 mg",
                        "Tablet",
                        "TZ persistence test",
                        ProductStatus.ACTIVE
                ));
                Recipe recipe = new Recipe(
                        "TZ-RCP-" + n,
                        product,
                        1,
                        new BigDecimal("100.0000"),
                        "kg",
                        RecipeStatus.APPROVED,
                        "TZ Recipe"
                );
                recipe.addStep(new RecipeStep(recipe, 1, "Mix", "Mix ingredients"));
                recipe = recipeRepository.saveAndFlush(recipe);

                ManufacturingOrder order = new ManufacturingOrder(
                        "TZ-MO-" + n,
                        product,
                        recipe,
                        1,
                        new BigDecimal("100.0000"),
                        "kg",
                        1,
                        OrderStatus.IN_PROGRESS,
                        dstSpringDate,
                        dstFallDate,
                        "DST date preservation"
                );
                order.setActualStartDate(istBoundary);
                order = orderRepository.saveAndFlush(order);

                Batch batch = new Batch(
                        "TZ-BAT-" + n,
                        order,
                        new BigDecimal("100.0000"),
                        "kg",
                        BatchStatus.IN_PROGRESS,
                        dstSpringDate,
                        leapDate
                );
                batch.setStartTime(istBoundary);
                batch = batchRepository.saveAndFlush(batch);

                Deviation deviation = new Deviation(
                        "TZ-DEV-" + n,
                        batch,
                        null,
                        null,
                        null,
                        "Midnight boundary deviation " + n,
                        DeviationCategory.PROCESS_PARAMETER,
                        DeviationSeverity.MAJOR,
                        DeviationPriority.HIGH,
                        DeviationStatus.OPEN,
                        "Cross-midnight test",
                        null,
                        "tz.tester",
                        istBoundary,
                        istBoundary
                );
                deviation = deviationRepository.saveAndFlush(deviation);

                CapaAction capa = capaActionRepository.saveAndFlush(new CapaAction(
                        "TZ-CAPA-" + n,
                        deviation,
                        CapaType.CORRECTIVE,
                        "Calibrate clock",
                        "Verify UTC storage",
                        "QA Owner",
                        dstFallDate,
                        CapaStatus.OPEN,
                        "tz.tester"
                ));

                AuditLog auditLog = auditLogRepository.save(new AuditLog(
                        "DEVIATION",
                        deviation.getId(),
                        deviation.getDeviationNumber(),
                        batch.getId(),
                        "TZ_BOUNDARY_CHECK",
                        "tz.tester",
                        istBoundary,
                        "Verify UTC instant storage",
                        null,
                        null
                ));

                return new Long[]{order.getId(), batch.getId(), deviation.getId(), capa.getId(), auditLog.getId()};
            });

            assertNotNull(ids);
            Long orderId = ids[0];
            Long batchId = ids[1];
            Long deviationId = ids[2];
            Long capaId = ids[3];
            Long auditId = ids[4];

            // Verify raw SQL column values stored in DB represent exact UTC (2026-11-10 18:35:00)
            // and exact unshifted YYYY-MM-DD calendar dates, regardless of writer JVM TZ.
            String rawSqlOrderStart = jdbcTemplate.queryForObject(
                    "SELECT CAST(actual_start_date AS VARCHAR) FROM orders WHERE id = ?", String.class, orderId);
            String rawSqlDeviationOccurred = jdbcTemplate.queryForObject(
                    "SELECT CAST(occurred_at AS VARCHAR) FROM deviations WHERE id = ?", String.class, deviationId);
            String rawSqlAuditOccurred = jdbcTemplate.queryForObject(
                    "SELECT CAST(occurred_at AS VARCHAR) FROM audit_logs WHERE id = ?", String.class, auditId);

            assertNotNull(rawSqlOrderStart);
            assertNotNull(rawSqlDeviationOccurred);
            assertNotNull(rawSqlAuditOccurred);
            assertTrue(rawSqlOrderStart.startsWith("2026-11-10 18:35:00"),
                    "Writer TZ " + writerZone + " stored wrong SQL timestamp in orders.actual_start_date: " + rawSqlOrderStart);
            assertTrue(rawSqlDeviationOccurred.startsWith("2026-11-10 18:35:00"),
                    "Writer TZ " + writerZone + " stored wrong SQL timestamp in deviations.occurred_at: " + rawSqlDeviationOccurred);
            assertTrue(rawSqlAuditOccurred.startsWith("2026-11-10 18:35:00"),
                    "Writer TZ " + writerZone + " stored wrong SQL timestamp in audit_logs.occurred_at: " + rawSqlAuditOccurred);

            Timestamp rawAuditUtcTs = jdbcTemplate.queryForObject(
                    "SELECT occurred_at FROM audit_logs WHERE id = ?",
                    (rs, rowNum) -> rs.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))),
                    auditId
            );
            assertNotNull(rawAuditUtcTs);
            assertEquals(expectedUtcInstant, rawAuditUtcTs.toInstant());

            assertEquals("2026-03-08", jdbcTemplate.queryForObject(
                    "SELECT CAST(planned_start_date AS VARCHAR) FROM orders WHERE id = ?", String.class, orderId));
            assertEquals("2026-11-01", jdbcTemplate.queryForObject(
                    "SELECT CAST(planned_end_date AS VARCHAR) FROM orders WHERE id = ?", String.class, orderId));
            assertEquals("2024-02-29", jdbcTemplate.queryForObject(
                    "SELECT CAST(expiry_date AS VARCHAR) FROM batches WHERE id = ?", String.class, batchId));
            assertEquals("2026-11-01", jdbcTemplate.queryForObject(
                    "SELECT CAST(due_date AS VARCHAR) FROM capa_actions WHERE id = ?", String.class, capaId));

            // Now read back through JPA across ALL 3 reader JVM timezones
            for (String readerZone : TEST_ZONES) {
                TimeZone.setDefault(TimeZone.getTimeZone(readerZone));
                transactionTemplate.executeWithoutResult(tx -> {
                    ManufacturingOrder reloadedOrder = orderRepository.findById(orderId).orElseThrow();
                    Batch reloadedBatch = batchRepository.findById(batchId).orElseThrow();
                    Deviation reloadedDev = deviationRepository.findById(deviationId).orElseThrow();
                    CapaAction reloadedCapa = capaActionRepository.findById(capaId).orElseThrow();
                    AuditLog reloadedAudit = auditLogRepository.findById(auditId).orElseThrow();

                    assertEquals(istBoundary, reloadedOrder.getActualStartDate(),
                            "Writer=" + writerZone + ", Reader=" + readerZone + " drifted order.actualStartDate");
                    assertEquals(istBoundary, reloadedBatch.getStartTime(),
                            "Writer=" + writerZone + ", Reader=" + readerZone + " drifted batch.startTime");
                    assertEquals(istBoundary, reloadedDev.getOccurredAt(),
                            "Writer=" + writerZone + ", Reader=" + readerZone + " drifted deviation.occurredAt");
                    assertEquals(istBoundary, reloadedAudit.getOccurredAt(),
                            "Writer=" + writerZone + ", Reader=" + readerZone + " drifted auditLog.occurredAt");

                    assertEquals(dstSpringDate, reloadedOrder.getPlannedStartDate());
                    assertEquals(dstFallDate, reloadedOrder.getPlannedEndDate());
                    assertEquals(dstSpringDate, reloadedBatch.getManufactureDate());
                    assertEquals(leapDate, reloadedBatch.getExpiryDate());
                    assertEquals(dstFallDate, reloadedCapa.getDueDate());
                });
            }
        }
    }

    @Test
    @DisplayName("2. Hibernate metamodel applies AsiaKolkataLocalDateTimeConverter using TIMESTAMP/DATETIME(6) and leaves LocalDate as DATE for MySQL ddl-auto=validate compatibility")
    void hibernateMetamodelPreservesMySqlDatetime6AndDateCompatibility() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        MySQL8DdlProbe mysqlDialect = new MySQL8DdlProbe();

        List<Class<?>> convertedTimestampEntities = List.of(
                AuditLog.class,
                Deviation.class,
                CapaAction.class,
                Batch.class,
                ManufacturingOrder.class,
                UserAccount.class
        );

        String[] timestampProps = {"occurredAt", "occurredAt", "createdAt", "startTime", "actualStartDate", "createdAt"};
        for (int i = 0; i < convertedTimestampEntities.size(); i++) {
            EntityPersister persister = sessionFactory.getMappingMetamodel()
                    .getEntityDescriptor(convertedTimestampEntities.get(i));
            BasicValuedModelPart modelPart = persister.findAttributeMapping(timestampProps[i]).asBasicValuedModelPart();
            JdbcMapping jdbcMapping = modelPart.getSingleJdbcMapping();

            assertNotNull(jdbcMapping.getValueConverter(),
                    "Expected autoApply AsiaKolkataLocalDateTimeConverter on "
                            + convertedTimestampEntities.get(i).getSimpleName() + "." + timestampProps[i]);
            assertEquals(LocalDateTime.class, jdbcMapping.getMappedJavaType().getJavaTypeClass());
            assertEquals(Timestamp.class, jdbcMapping.getJdbcJavaType().getJavaTypeClass());
            assertEquals(SqlTypes.TIMESTAMP, jdbcMapping.getJdbcType().getDdlTypeCode());
            assertTrue(mysqlDialect.resolveColumnType(jdbcMapping.getJdbcType().getDdlTypeCode()).startsWith("datetime"),
                    "MySQL DDL type must remain datetime(6) for validate mode compatibility");
        }

        EntityPersister orderPersister = sessionFactory.getMappingMetamodel().getEntityDescriptor(ManufacturingOrder.class);
        JdbcMapping plannedStartMapping = orderPersister.findAttributeMapping("plannedStartDate")
                .asBasicValuedModelPart().getSingleJdbcMapping();
        assertNull(plannedStartMapping.getValueConverter());
        assertEquals(LocalDate.class, plannedStartMapping.getMappedJavaType().getJavaTypeClass());
        assertEquals(LocalDate.class, plannedStartMapping.getJdbcJavaType().getJavaTypeClass());
        assertEquals(SqlTypes.LOCAL_DATE, plannedStartMapping.getJdbcType().getJdbcTypeCode());
        assertEquals(SqlTypes.DATE, plannedStartMapping.getJdbcType().getDdlTypeCode());
        assertEquals("date", mysqlDialect.resolveColumnType(plannedStartMapping.getJdbcType().getDdlTypeCode()));
    }

    @Test
    @DisplayName("3. HTTP responses serialize all timestamps with +05:30 offset (including Health, Errors, Entities, and Audit snapshots) while keeping LocalDate as YYYY-MM-DD under UTC and New York JVMs")
    void httpJsonResponsesEmitExplicitPlus0530AndPreserveCalendarDates() throws Exception {
        String adminBearer = bearerFor("tz.http.admin", Role.ADMIN);
        String qaBearer = bearerFor("tz.http.qa", Role.QA_REVIEWER);
        String supervisorBearer = bearerFor("tz.http.supervisor", Role.PRODUCTION_SUPERVISOR);

        for (String zoneId : List.of("UTC", "America/New_York")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
            int n = SEQ.getAndIncrement();

            // A. /api/health emits +05:30 timestamp
            MvcResult healthRes = mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode healthJson = objectMapper.readTree(healthRes.getResponse().getContentAsString());
            assertIstOffsetString(healthJson.get("timestamp").asText(), "/api/health timestamp under " + zoneId);

            // B. Standard ErrorResponse emits +05:30 timestamp
            MvcResult errRes = mockMvc.perform(get("/api/products/9999999")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer))
                    .andExpect(status().isNotFound())
                    .andReturn();
            JsonNode errJson = objectMapper.readTree(errRes.getResponse().getContentAsString());
            assertIstOffsetString(errJson.get("timestamp").asText(), "ErrorResponse timestamp under " + zoneId);

            // C. POST /api/products & GET /api/quality/audit-logs emit +05:30 including inside audit afterValues JSON
            ProductCreateRequestDto productDto = new ProductCreateRequestDto(
                    null,
                    "TZ-HTTP-" + n,
                    "TZ HTTP Product " + n,
                    "500 mg",
                    "Tablet",
                    "Wire contract check",
                    ProductStatus.ACTIVE
            );
            MvcResult prodRes = mockMvc.perform(post("/api/products")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(productDto)))
                    .andExpect(status().isCreated())
                    .andReturn();
            JsonNode prodJson = objectMapper.readTree(prodRes.getResponse().getContentAsString());
            long productId = prodJson.get("id").asLong();
            assertIstOffsetString(prodJson.get("createdAt").asText(), "Product.createdAt under " + zoneId);
            assertIstOffsetString(prodJson.get("updatedAt").asText(), "Product.updatedAt under " + zoneId);

            MvcResult auditRes = mockMvc.perform(get("/api/quality/audit-logs")
                            .header(HttpHeaders.AUTHORIZATION, qaBearer)
                            .param("entityType", "PRODUCT")
                            .param("entityId", String.valueOf(productId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andReturn();
            JsonNode auditNode = objectMapper.readTree(auditRes.getResponse().getContentAsString()).get(0);
            assertIstOffsetString(auditNode.get("occurredAt").asText(), "AuditLog.occurredAt under " + zoneId);

            // Verify audit afterValues serialized via ManufacturingOrder / Product has +05:30 where timestamps appear
            Product product = productRepository.findById(productId).orElseThrow();
            Recipe recipe = new Recipe(
                    "TZ-HTTP-RCP-" + n,
                    product,
                    1,
                    new BigDecimal("100.0000"),
                    "kg",
                    RecipeStatus.APPROVED,
                    "HTTP Contract Recipe"
            );
            recipe.addStep(new RecipeStep(recipe, 1, "Blend", "Blend 10 mins"));
            recipe = recipeRepository.saveAndFlush(recipe);

            LocalDate plannedStart = AppTime.nowDate().plusDays(1);
            LocalDate plannedEnd = AppTime.nowDate().plusDays(5);
            OrderRequestDto orderReq = new OrderRequestDto(
                    product.getId(),
                    recipe.getId(),
                    new BigDecimal("100.0000"),
                    "kg",
                    plannedStart,
                    plannedEnd,
                    "Calendar date contract"
            );
            MvcResult orderRes = mockMvc.perform(post("/api/orders")
                            .header(HttpHeaders.AUTHORIZATION, supervisorBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(orderReq)))
                    .andExpect(status().isCreated())
                    .andReturn();
            JsonNode orderJson = objectMapper.readTree(orderRes.getResponse().getContentAsString());
            assertEquals(plannedStart.toString(), orderJson.get("plannedStartDate").asText());
            assertEquals(plannedEnd.toString(), orderJson.get("plannedEndDate").asText());
            assertIstOffsetString(orderJson.get("createdAt").asText(), "Order.createdAt under " + zoneId);

            long orderId = orderJson.get("id").asLong();
            MvcResult moAuditRes = mockMvc.perform(get("/api/quality/audit-logs")
                            .header(HttpHeaders.AUTHORIZATION, qaBearer)
                            .param("entityType", "MANUFACTURING_ORDER")
                            .param("entityId", String.valueOf(orderId)))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode moAuditNode = objectMapper.readTree(moAuditRes.getResponse().getContentAsString()).get(0);
            assertIstOffsetString(moAuditNode.get("occurredAt").asText(), "Order audit occurredAt under " + zoneId);
            JsonNode moAfterSnapshot = objectMapper.readTree(moAuditNode.get("afterValues").asText());
            assertEquals(plannedStart.toString(), moAfterSnapshot.get("plannedStartDate").asText());
            assertEquals(plannedEnd.toString(), moAfterSnapshot.get("plannedEndDate").asText());
        }
    }

    @Test
    @DisplayName("4. HTTP endpoints deserialize Z, +05:30, New York offset, and offset-free ISO inputs into identical Asia/Kolkata instants under UTC and New York JVMs")
    void httpEndpointsNormalizeOffsetAwareAndOffsetFreeInputsConsistently() throws Exception {
        String reporterBearer = bearerFor("tz.http.supervisor2", Role.PRODUCTION_SUPERVISOR);
        String qcBearer = bearerFor("tz.http.qc", Role.QC_ANALYST);

        int n = SEQ.getAndIncrement();
        Product product = productRepository.saveAndFlush(new Product(
                "TZ-INP-" + n,
                "TZ Input Contract Product",
                "500 mg",
                "Tablet",
                null,
                ProductStatus.ACTIVE
        ));
        Recipe recipe = new Recipe(
                "TZ-INP-RCP-" + n,
                product,
                1,
                new BigDecimal("100.0000"),
                "kg",
                RecipeStatus.APPROVED,
                null
        );
        recipe.addStep(new RecipeStep(recipe, 1, "Blend", "Blend step"));
        recipe = recipeRepository.saveAndFlush(recipe);

        ManufacturingOrder order = orderRepository.saveAndFlush(new ManufacturingOrder(
                "TZ-INP-MO-" + n,
                product,
                recipe,
                1,
                new BigDecimal("100.0000"),
                "kg",
                2,
                OrderStatus.IN_PROGRESS,
                AppTime.nowDate(),
                AppTime.nowDate().plusDays(2),
                null
        ));

        Batch inProgressBatch = batchRepository.saveAndFlush(new Batch(
                "TZ-INP-BAT-RUN-" + n,
                order,
                new BigDecimal("100.0000"),
                "kg",
                BatchStatus.IN_PROGRESS,
                AppTime.nowDate(),
                null
        ));

        Batch completedBatch = new Batch(
                "TZ-INP-BAT-DONE-" + n,
                order,
                new BigDecimal("100.0000"),
                "kg",
                BatchStatus.COMPLETED,
                AppTime.nowDate(),
                null
        );
        completedBatch.setQaStatus(BatchQualityStatus.PENDING_QA);
        completedBatch.setActualYield(new BigDecimal("99.5000"));
        completedBatch.setStartTime(AppTime.nowDateTime().minusHours(3));
        completedBatch.setEndTime(AppTime.nowDateTime().minusHours(1));
        completedBatch = batchRepository.saveAndFlush(completedBatch);

        // Pick a safe past instant at 00:05:00 IST so it is strictly past AND crosses UTC midnight (18:35Z previous day)
        LocalDateTime expectedIst = AppTime.nowDate().minusDays(2).atTime(0, 5, 0);
        ZonedDateTime istZdt = expectedIst.atZone(AppTime.APP_ZONE_ID);
        ZonedDateTime utcZdt = istZdt.withZoneSameInstant(ZoneOffset.UTC);
        assertNotEquals(utcZdt.toLocalDate(), expectedIst.toLocalDate(),
                "Test instant 00:05 IST must fall on the previous calendar day in UTC (18:35Z)");

        String expectedWireIst = AppTime.formatIsoTimestamp(expectedIst);
        String expectedSqlPrefix = utcZdt.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        List<String> equivalentWireTimestamps = List.of(
                utcZdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),                                  // ...T18:35:00Z
                istZdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),                                  // ...T00:05:00+05:30
                istZdt.withZoneSameInstant(ZoneId.of("America/New_York")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                expectedIst.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)                               // offset-free ...T00:05:00
        );

        for (String zoneId : List.of("UTC", "America/New_York")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

            for (int idx = 0; idx < equivalentWireTimestamps.size(); idx++) {
                String wireTs = equivalentWireTimestamps.get(idx);

                // A. Deviation occurredAt deserialization & canonical +05:30 serialization
                String devBody = objectMapper.writeValueAsString(new DeviationHttpPayload(
                        inProgressBatch.getId(),
                        "TZ input equivalence " + zoneId + " #" + idx,
                        "PROCESS_PARAMETER",
                        "MINOR",
                        "LOW",
                        "Testing " + wireTs,
                        wireTs
                ));

                MvcResult devResult = mockMvc.perform(post("/api/quality/deviations")
                                .header(HttpHeaders.AUTHORIZATION, reporterBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(devBody))
                        .andExpect(status().isCreated())
                        .andReturn();
                JsonNode devJson = objectMapper.readTree(devResult.getResponse().getContentAsString());
                assertEquals(expectedWireIst, devJson.get("occurredAt").asText(),
                        "occurredAt mismatch for wire input " + wireTs + " under JVM TZ " + zoneId);
                assertIstOffsetString(devJson.get("reportedAt").asText(), "Deviation.reportedAt under " + zoneId);

                long deviationId = devJson.get("id").asLong();
                assertEquals(expectedIst, deviationRepository.findById(deviationId).orElseThrow().getOccurredAt());
                String rawDevSql = jdbcTemplate.queryForObject(
                        "SELECT CAST(occurred_at AS VARCHAR) FROM deviations WHERE id = ?", String.class, deviationId);
                assertNotNull(rawDevSql);
                assertTrue(rawDevSql.startsWith(expectedSqlPrefix),
                        "Expected UTC SQL " + expectedSqlPrefix + " but got " + rawDevSql);

                // B. QualityCheck checkedAt deserialization & canonical +05:30 serialization
                String qcBody = objectMapper.writeValueAsString(new QualityCheckHttpPayload(
                        "Assay " + zoneId + " #" + idx,
                        "95-105",
                        "99.0",
                        "%",
                        "PASS",
                        true,
                        wireTs,
                        "Wire format check"
                ));

                MvcResult qcResult = mockMvc.perform(post("/api/quality/batches/{id}/checks", completedBatch.getId())
                                .header(HttpHeaders.AUTHORIZATION, qcBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(qcBody))
                        .andExpect(status().isCreated())
                        .andReturn();
                JsonNode qcJson = objectMapper.readTree(qcResult.getResponse().getContentAsString());
                assertEquals(expectedWireIst, qcJson.get("checkedAt").asText(),
                        "checkedAt mismatch for wire input " + wireTs + " under JVM TZ " + zoneId);

                long checkId = qcJson.get("id").asLong();
                assertEquals(expectedIst, qualityCheckRepository.findById(checkId).orElseThrow().getCheckedAt());
                String rawQcSql = jdbcTemplate.queryForObject(
                        "SELECT CAST(checked_at AS VARCHAR) FROM quality_checks WHERE id = ?", String.class, checkId);
                assertNotNull(rawQcSql);
                assertTrue(rawQcSql.startsWith(expectedSqlPrefix),
                        "Expected UTC SQL " + expectedSqlPrefix + " but got " + rawQcSql);
            }

            // C. Invalid ISO timestamp returns HTTP 400 with +05:30 error timestamp
            String badBody = objectMapper.writeValueAsString(new QualityCheckHttpPayload(
                    "Invalid TS",
                    "95-105",
                    "99.0",
                    "%",
                    "PASS",
                    true,
                    "2026-11-11T99:00:00",
                    null
            ));
            MvcResult badRes = mockMvc.perform(post("/api/quality/batches/{id}/checks", completedBatch.getId())
                            .header(HttpHeaders.AUTHORIZATION, qcBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badBody))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            JsonNode badJson = objectMapper.readTree(badRes.getResponse().getContentAsString());
            assertIstOffsetString(badJson.get("timestamp").asText(), "Malformed timestamp 400 ErrorResponse under " + zoneId);
        }
    }

    private static void assertIstOffsetString(String value, String label) {
        assertNotNull(value, label + " must not be null");
        assertTrue(IST_OFFSET_ISO_PATTERN.matcher(value).matches(),
                label + " must emit explicit +05:30 ISO timestamp, but was: " + value);
    }

    private static final class MySQL8DdlProbe extends MySQLDialect {
        private MySQL8DdlProbe() {
            super(DatabaseVersion.make(8, 0));
        }

        private String resolveColumnType(int sqlTypeCode) {
            return super.columnType(sqlTypeCode);
        }
    }

    private record DeviationHttpPayload(
            Long batchId,
            String title,
            String category,
            String severity,
            String priority,
            String description,
            String occurredAt
    ) {
    }

    private record QualityCheckHttpPayload(
            String testName,
            String specification,
            String measuredValue,
            String unit,
            String result,
            Boolean required,
            String checkedAt,
            String remarks
    ) {
    }
}
