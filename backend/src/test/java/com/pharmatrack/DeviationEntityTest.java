package com.pharmatrack;

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
import com.pharmatrack.exception.InvalidDeviationOperationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Deviations & CAPA domain model enums, JPA entities,
 * Bean Validation rules, lifecycle callbacks, cascade protections, and proxy-safe equals/hashCode.
 */
class DeviationEntityTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private Batch createSampleBatch() {
        Product product = new Product("AMOX-250", "Amoxicillin Capsules", "250 mg", "Capsule", "Antibiotic", ProductStatus.ACTIVE);
        product.setId(1L);
        Recipe recipe = new Recipe("RCP-AMOX-250-V3", product, 3, new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "Master V3");
        recipe.setId(10L);
        ManufacturingOrder order = new ManufacturingOrder(
                "MO-2026-0001", product, recipe, 3, new BigDecimal("250.0000"), "kg", 1,
                OrderStatus.COMPLETED, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), "Run 1"
        );
        order.setId(100L);
        Batch batch = new Batch("BAT-2026-0001-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        batch.setId(500L);
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);
        return batch;
    }

    private Deviation createValidDeviation() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 14, 0);
        return new Deviation(
                "DEV-2026-0001",
                createSampleBatch(),
                null,
                null,
                null,
                "Dissolution test below 80% specification",
                DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.MAJOR,
                DeviationPriority.HIGH,
                DeviationStatus.OPEN,
                "Observed 65% dissolution at 30 minutes against minimum 80% acceptance specification.",
                "Batch held in PENDING_QA pending investigation.",
                "A. Nair",
                now.minusMinutes(15),
                now
        );
    }

    private CapaAction createValidCapa(Deviation deviation) {
        return new CapaAction(
                "CAPA-2026-0001",
                deviation,
                CapaType.CORRECTIVE,
                "Verify granulator compression settings and SOP-GRN-04",
                "Inspect compression dwell force logs and retrain line operators on hopper speed limits.",
                "M. Rao",
                LocalDate.of(2026, 11, 20),
                CapaStatus.OPEN,
                "Dr. Menon"
        );
    }

    @Test
    @DisplayName("1. Deviation and CAPA enums define all expected lifecycle and classification values")
    void enumsContainAllRequiredValues() {
        assertEquals(6, DeviationStatus.values().length);
        assertNotNull(DeviationStatus.valueOf("OPEN"));
        assertNotNull(DeviationStatus.valueOf("UNDER_INVESTIGATION"));
        assertNotNull(DeviationStatus.valueOf("ROOT_CAUSE_IDENTIFIED"));
        assertNotNull(DeviationStatus.valueOf("CAPA_IN_PROGRESS"));
        assertNotNull(DeviationStatus.valueOf("PENDING_VERIFICATION"));
        assertNotNull(DeviationStatus.valueOf("CLOSED"));

        assertEquals(8, DeviationCategory.values().length);
        assertNotNull(DeviationCategory.valueOf("QC_OOS_FAILURE"));
        assertNotNull(DeviationCategory.valueOf("PROCESS_PARAMETER"));
        assertNotNull(DeviationCategory.valueOf("IN_PROCESS_CONTROL"));
        assertNotNull(DeviationCategory.valueOf("EQUIPMENT_MALFUNCTION"));
        assertNotNull(DeviationCategory.valueOf("MATERIAL_ISSUE"));
        assertNotNull(DeviationCategory.valueOf("DOCUMENTATION_ERROR"));
        assertNotNull(DeviationCategory.valueOf("ENVIRONMENTAL_EXCURSION"));
        assertNotNull(DeviationCategory.valueOf("OTHER"));

        assertEquals(3, DeviationSeverity.values().length);
        assertEquals(3, DeviationPriority.values().length);
        assertEquals(2, CapaType.values().length);
        assertEquals(4, CapaStatus.values().length);
    }

    @Test
    @DisplayName("2. Deviation PrePersist and PreUpdate callbacks initialize defaults and refresh timestamps")
    void deviationCallbacksInitializeDefaultsAndRefreshTimestamps() throws Exception {
        Deviation deviation = new Deviation();
        assertNull(deviation.getStatus());

        Method onCreate = Deviation.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(deviation);

        assertEquals(DeviationStatus.OPEN, deviation.getStatus());
        assertNotNull(deviation.getCreatedAt());
        assertNotNull(deviation.getUpdatedAt());
        assertNotNull(deviation.getOccurredAt());
        assertNotNull(deviation.getReportedAt());

        LocalDateTime past = LocalDateTime.of(2026, 1, 1, 8, 0);
        deviation.setUpdatedAt(past);

        Method onUpdate = Deviation.class.getDeclaredMethod("onUpdate");
        onUpdate.setAccessible(true);
        onUpdate.invoke(deviation);

        assertTrue(deviation.getUpdatedAt().isAfter(past));
    }

    @Test
    @DisplayName("3. CapaAction PrePersist and PreUpdate callbacks initialize defaults and refresh timestamps")
    void capaCallbacksInitializeDefaultsAndRefreshTimestamps() throws Exception {
        CapaAction capa = new CapaAction();
        assertNull(capa.getStatus());

        Method onCreate = CapaAction.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(capa);

        assertEquals(CapaStatus.OPEN, capa.getStatus());
        assertNotNull(capa.getCreatedAt());
        assertNotNull(capa.getUpdatedAt());

        LocalDateTime past = LocalDateTime.of(2026, 1, 1, 8, 0);
        capa.setUpdatedAt(past);

        Method onUpdate = CapaAction.class.getDeclaredMethod("onUpdate");
        onUpdate.setAccessible(true);
        onUpdate.invoke(capa);

        assertTrue(capa.getUpdatedAt().isAfter(past));
    }

    @Test
    @DisplayName("4. Deviation.addCapaAction maintains bidirectional link and cascade rules protect CAPA history")
    void deviationCapaCascadeProtectsHistory() throws NoSuchFieldException {
        Deviation deviation = createValidDeviation();
        CapaAction capa = createValidCapa(null);

        assertTrue(deviation.getCapaActions().isEmpty());
        deviation.addCapaAction(capa);
        assertEquals(1, deviation.getCapaActions().size());
        assertEquals(deviation, capa.getDeviation());

        deviation.addCapaAction(null);
        assertEquals(1, deviation.getCapaActions().size());

        deviation.setCapaActions(null);
        assertNotNull(deviation.getCapaActions());
        assertTrue(deviation.getCapaActions().isEmpty());

        Field capaField = Deviation.class.getDeclaredField("capaActions");
        OneToMany oneToMany = capaField.getAnnotation(OneToMany.class);
        assertNotNull(oneToMany);
        assertFalse(oneToMany.orphanRemoval(), "orphanRemoval must be false so CAPA records cannot be silently deleted");

        List<CascadeType> cascades = Arrays.asList(oneToMany.cascade());
        assertEquals(2, cascades.size());
        assertTrue(cascades.contains(CascadeType.PERSIST));
        assertTrue(cascades.contains(CascadeType.MERGE));
        assertFalse(cascades.contains(CascadeType.REMOVE));
        assertFalse(cascades.contains(CascadeType.ALL));

        boolean hasRemoveCapa = Arrays.stream(Deviation.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("removeCapaAction"));
        assertFalse(hasRemoveCapa, "Deviation must not expose removeCapaAction");
    }

    @Test
    @DisplayName("5. Valid Deviation and CapaAction pass Bean Validation; blank instances fail mandatory checks")
    void beanValidationEnforcesMandatoryAndLengthRules() {
        Deviation validDeviation = createValidDeviation();
        assertTrue(validator.validate(validDeviation).isEmpty());

        CapaAction validCapa = createValidCapa(validDeviation);
        assertTrue(validator.validate(validCapa).isEmpty());

        Deviation blankDeviation = new Deviation();
        Set<String> devViolations = validator.validate(blankDeviation).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertTrue(devViolations.contains("deviationNumber"));
        assertTrue(devViolations.contains("batch"));
        assertTrue(devViolations.contains("title"));
        assertTrue(devViolations.contains("category"));
        assertTrue(devViolations.contains("severity"));
        assertTrue(devViolations.contains("priority"));
        assertTrue(devViolations.contains("status"));
        assertTrue(devViolations.contains("description"));
        assertTrue(devViolations.contains("reportedBy"));
        assertTrue(devViolations.contains("occurredAt"));
        assertTrue(devViolations.contains("reportedAt"));

        CapaAction blankCapa = new CapaAction();
        Set<String> capaViolations = validator.validate(blankCapa).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertTrue(capaViolations.contains("capaNumber"));
        assertTrue(capaViolations.contains("deviation"));
        assertTrue(capaViolations.contains("capaType"));
        assertTrue(capaViolations.contains("title"));
        assertTrue(capaViolations.contains("actionPlan"));
        assertTrue(capaViolations.contains("ownerName"));
        assertTrue(capaViolations.contains("dueDate"));
        assertTrue(capaViolations.contains("status"));
        assertTrue(capaViolations.contains("createdBy"));

        validDeviation.setTitle("T".repeat(201));
        assertTrue(validator.validate(validDeviation).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("title")));
    }

    @Test
    @DisplayName("6. Deviation and CapaAction adhere to Hibernate-proxy-safe equals/hashCode contract")
    void equalsAndHashCodeContract() {
        Deviation d1 = new Deviation();
        d1.setId(1L);
        d1.setDeviationNumber("DEV-2026-0001");

        Deviation d2 = new Deviation();
        d2.setId(2L);
        d2.setDeviationNumber("DEV-2026-0001");

        Deviation d3 = new Deviation();
        d3.setId(3L);
        d3.setDeviationNumber("DEV-2026-0002");

        assertEquals(d1, d1);
        assertEquals(d1, d2);
        assertEquals(Deviation.class.hashCode(), d1.hashCode());
        assertNotEquals(d1, d3);
        assertNotEquals(d1, null);

        Deviation nullKeyA = new Deviation();
        Deviation nullKeyB = new Deviation();
        assertEquals(nullKeyA, nullKeyA);
        assertNotEquals(nullKeyA, nullKeyB);
        assertNotEquals(nullKeyA, d1);

        class DeviationProxy extends Deviation {
            @Override
            public String getDeviationNumber() {
                return "DEV-2026-0001";
            }
        }
        Deviation proxy = new DeviationProxy();
        assertEquals(d1, proxy);
        assertEquals(proxy, d1);

        CapaAction c1 = new CapaAction();
        c1.setCapaNumber("CAPA-2026-0001");
        CapaAction c2 = new CapaAction();
        c2.setCapaNumber("CAPA-2026-0001");
        CapaAction c3 = new CapaAction();
        c3.setCapaNumber("CAPA-2026-0002");

        assertEquals(c1, c2);
        assertEquals(CapaAction.class.hashCode(), c1.hashCode());
        assertNotEquals(c1, c3);
        assertNotEquals(new CapaAction(), new CapaAction());

        InvalidDeviationOperationException ex = new InvalidDeviationOperationException("Closure blocked");
        assertEquals("Closure blocked", ex.getMessage());
    }
}

