package com.pharmatrack;

import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.util.AppTime;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProductEntityTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Should pass validation when all mandatory fields are provided")
    void validProductShouldPassValidation() {
        Product product = new Product(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Analgesic and antipyretic medication",
                ProductStatus.ACTIVE
        );

        Set<ConstraintViolation<Product>> violations = validator.validate(product);
        assertTrue(violations.isEmpty(), "Valid product should have no validation violations");
    }

    @Test
    @DisplayName("Should fail validation when mandatory fields are blank or null")
    void invalidProductShouldFailValidation() {
        Product product = new Product();
        // Mandatory fields are left null

        Set<ConstraintViolation<Product>> violations = validator.validate(product);
        assertFalse(violations.isEmpty(), "Blank product must produce validation violations");

        // Verify violation property paths
        Set<String> violatedFields = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(violatedFields.contains("productCode"));
        assertTrue(violatedFields.contains("productName"));
        assertTrue(violatedFields.contains("strength"));
        assertTrue(violatedFields.contains("dosageForm"));
        assertTrue(violatedFields.contains("status"));
    }

    @Test
    @DisplayName("PrePersist should initialize timestamps and default status")
    void prePersistShouldPopulateTimestampsAndStatus() throws Exception {
        Product product = new Product();
        product.setProductCode("PRD-002");
        product.setProductName("Amoxicillin");
        product.setStrength("250 mg");
        product.setDosageForm("Capsule");

        assertNull(product.getCreatedAt());
        assertNull(product.getUpdatedAt());
        assertNull(product.getStatus());

        // Invoke reflection or lifecycle method directly
        java.lang.reflect.Method prePersistMethod = Product.class.getDeclaredMethod("onCreate");
        prePersistMethod.setAccessible(true);
        prePersistMethod.invoke(product);

        assertNotNull(product.getCreatedAt());
        assertNotNull(product.getUpdatedAt());
        assertEquals(ProductStatus.ACTIVE, product.getStatus());
    }

    @Test
    @DisplayName("PreUpdate should update updatedAt timestamp")
    void preUpdateShouldRefreshUpdatedAt() throws Exception {
        Product product = new Product();
        LocalDateTime past = AppTime.nowDateTime().minusHours(2);
        product.setCreatedAt(past);
        product.setUpdatedAt(past);

        java.lang.reflect.Method preUpdateMethod = Product.class.getDeclaredMethod("onUpdate");
        preUpdateMethod.setAccessible(true);
        preUpdateMethod.invoke(product);

        assertTrue(product.getUpdatedAt().isAfter(past));
        assertEquals(past, product.getCreatedAt());
    }

    @Test
    @DisplayName("Equals and HashCode should be consistent based on productCode")
    void equalsAndHashCodeShouldBeConsistent() {
        Product p1 = new Product("PRD-100", "Aspirin", "100 mg", "Tablet", null, ProductStatus.ACTIVE);
        Product p2 = new Product("PRD-100", "Aspirin Generic", "100 mg", "Tablet", "Different description", ProductStatus.ACTIVE);
        Product p3 = new Product("PRD-200", "Ibuprofen", "400 mg", "Tablet", null, ProductStatus.ACTIVE);

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
        assertNotEquals(p1, p3);
    }
}

