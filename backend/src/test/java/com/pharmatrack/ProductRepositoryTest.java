package com.pharmatrack;

import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("Should save a product and generate id and timestamps")
    void shouldSaveProductSuccessfully() {
        Product product = new Product(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Analgesic and antipyretic",
                ProductStatus.ACTIVE
        );

        Product savedProduct = productRepository.save(product);

        assertNotNull(savedProduct.getId(), "Generated ID should not be null");
        assertNotNull(savedProduct.getCreatedAt(), "CreatedAt timestamp should be populated");
        assertNotNull(savedProduct.getUpdatedAt(), "UpdatedAt timestamp should be populated");
        assertEquals("PRD-001", savedProduct.getProductCode());
        assertEquals("Paracetamol", savedProduct.getProductName());
    }

    @Test
    @DisplayName("Should find product by unique product code when it exists")
    void shouldFindProductByProductCodeWhenExists() {
        Product product = new Product(
                "PRD-002",
                "Amoxicillin",
                "250 mg",
                "Capsule",
                "Broad-spectrum antibiotic",
                ProductStatus.ACTIVE
        );
        entityManager.persistAndFlush(product);

        Optional<Product> foundProduct = productRepository.findByProductCode("PRD-002");

        assertTrue(foundProduct.isPresent(), "Product should be found by product code");
        assertEquals("Amoxicillin", foundProduct.get().getProductName());
        assertEquals("Capsule", foundProduct.get().getDosageForm());
    }

    @Test
    @DisplayName("Should return empty Optional when searching for a non-existent product code")
    void shouldReturnEmptyOptionalWhenProductCodeNotFound() {
        Optional<Product> foundProduct = productRepository.findByProductCode("NON-EXISTENT-CODE");

        assertTrue(foundProduct.isEmpty(), "Optional should be empty for non-existent product code");
    }

    @Test
    @DisplayName("Should return true when checking existence of an existing product code")
    void shouldReturnTrueWhenProductCodeExists() {
        Product product = new Product(
                "PRD-003",
                "Ibuprofen",
                "400 mg",
                "Tablet",
                "NSAID",
                ProductStatus.ACTIVE
        );
        entityManager.persistAndFlush(product);

        boolean exists = productRepository.existsByProductCode("PRD-003");

        assertTrue(exists, "existsByProductCode should return true for existing code");
    }

    @Test
    @DisplayName("Should return false when checking existence of a non-existent product code")
    void shouldReturnFalseWhenProductCodeDoesNotExist() {
        boolean exists = productRepository.existsByProductCode("UNKNOWN-999");

        assertFalse(exists, "existsByProductCode should return false for unknown code");
    }

    @Test
    @DisplayName("Should filter products by status (ACTIVE and INACTIVE)")
    void shouldFilterProductsByStatus() {
        Product active1 = new Product("PRD-ACT-1", "Metformin", "500 mg", "Tablet", null, ProductStatus.ACTIVE);
        Product active2 = new Product("PRD-ACT-2", "Atorvastatin", "20 mg", "Tablet", null, ProductStatus.ACTIVE);
        Product inactive1 = new Product("PRD-INA-1", "Ranitidine", "150 mg", "Tablet", "Recalled formulation", ProductStatus.INACTIVE);

        entityManager.persist(active1);
        entityManager.persist(active2);
        entityManager.persist(inactive1);
        entityManager.flush();

        List<Product> activeProducts = productRepository.findByStatus(ProductStatus.ACTIVE);
        List<Product> inactiveProducts = productRepository.findByStatus(ProductStatus.INACTIVE);

        assertEquals(2, activeProducts.size(), "Should find exactly 2 ACTIVE products");
        assertTrue(activeProducts.stream().allMatch(p -> p.getStatus() == ProductStatus.ACTIVE));

        assertEquals(1, inactiveProducts.size(), "Should find exactly 1 INACTIVE product");
        assertEquals("PRD-INA-1", inactiveProducts.get(0).getProductCode());
    }

    @Test
    @DisplayName("Should enforce uniqueness constraint on productCode")
    void shouldThrowExceptionWhenDuplicateProductCodePersisted() {
        Product product1 = new Product("PRD-DUP", "Product One", "10 mg", "Tablet", null, ProductStatus.ACTIVE);
        entityManager.persistAndFlush(product1);

        Product product2 = new Product("PRD-DUP", "Product Two", "20 mg", "Capsule", null, ProductStatus.ACTIVE);

        assertThrows(DataIntegrityViolationException.class, () -> {
            productRepository.saveAndFlush(product2);
        }, "Saving a duplicate productCode should throw DataIntegrityViolationException");
    }
}

