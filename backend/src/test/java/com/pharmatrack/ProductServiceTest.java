package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.InvalidQualityOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditTrailService auditTrailService;
    private ProductService productService;
    private ObjectMapper objectMapper;
    private Product sampleProduct;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        auditTrailService = new AuditTrailService(auditLogRepository, objectMapper);
        productService = new ProductService(productRepository, auditTrailService);

        sampleProduct = new Product(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Analgesic medication",
                ProductStatus.ACTIVE
        );
        sampleProduct.setId(1L);
        authenticateAs("system.admin", "ADMIN");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String... roles) {
        String[] authorities = new String[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = roles[i].startsWith("ROLE_") ? roles[i] : "ROLE_" + roles[i];
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", AuthorityUtils.createAuthorityList(authorities))
        );
    }

    @Test
    @DisplayName("Should successfully create a product and write PRODUCT_CREATED audit log attributed to authenticated user")
    void shouldCreateProductSuccessfullyAndRecordAudit() throws Exception {
        Product transientProduct = new Product(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Analgesic medication",
                ProductStatus.ACTIVE
        );
        when(productRepository.existsByProductCode("PRD-001")).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenReturn(sampleProduct);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog log = inv.getArgument(0);
            log.setId(10L);
            return log;
        });

        Product created = productService.createProduct(transientProduct);

        assertNotNull(created);
        assertEquals("PRD-001", created.getProductCode());
        assertEquals("Paracetamol", created.getProductName());
        verify(productRepository, times(1)).existsByProductCode("PRD-001");
        verify(productRepository, times(1)).saveAndFlush(transientProduct);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("PRODUCT", audit.getEntityType());
        assertEquals(1L, audit.getEntityId());
        assertEquals("PRD-001", audit.getEntityReference());
        assertNull(audit.getBatchId());
        assertEquals("PRODUCT_CREATED", audit.getAction());
        assertEquals("system.admin", audit.getActorName());
        assertNotNull(audit.getOccurredAt());
        assertTrue(audit.getReason().contains("PRD-001"));
        assertNull(audit.getBeforeValues());

        JsonNode after = objectMapper.readTree(audit.getAfterValues());
        assertEquals("PRD-001", after.get("productCode").asText());
        assertEquals("Paracetamol", after.get("productName").asText());
        assertEquals("500 mg", after.get("strength").asText());
        assertEquals("Tablet", after.get("dosageForm").asText());
        assertEquals("Analgesic medication", after.get("description").asText());
        assertEquals("ACTIVE", after.get("status").asText());
    }

    @Test
    @DisplayName("Should successfully create a product from ProductCreateRequestDto and record PRODUCT_CREATED audit")
    void shouldCreateProductFromDtoSuccessfully() {
        authenticateAs("  Regulatory.Lead  ", "ADMIN");
        ProductCreateRequestDto dto = new ProductCreateRequestDto(
                "PRD-DTO-001",
                "Ibuprofen",
                "400 mg",
                "Tablet",
                "NSAID",
                ProductStatus.ACTIVE
        );
        Product persisted = new Product("PRD-DTO-001", "Ibuprofen", "400 mg", "Tablet", "NSAID", ProductStatus.ACTIVE);
        persisted.setId(22L);

        when(productRepository.existsByProductCode("PRD-DTO-001")).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(inv -> {
            Product arg = inv.getArgument(0);
            assertNull(arg.getId(), "Transient product entity constructed from DTO must have null id");
            return persisted;
        });
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        Product created = productService.createProduct(dto);

        assertEquals(22L, created.getId());
        assertEquals("PRD-DTO-001", created.getProductCode());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        assertEquals("PRODUCT_CREATED", captor.getValue().getAction());
        assertEquals("regulatory.lead", captor.getValue().getActorName());
        assertEquals(22L, captor.getValue().getEntityId());
    }

    @Test
    @DisplayName("Should reject product creation when an existing or non-null ID is supplied on DTO or Entity")
    void shouldRejectNonNullIdOnProductCreation() {
        ProductCreateRequestDto dtoWithId = new ProductCreateRequestDto(
                1L,
                "PRD-HIJACK",
                "Hijacked Name",
                "100 mg",
                "Capsule",
                "Should never overwrite",
                ProductStatus.INACTIVE
        );

        InvalidQualityOperationException dtoEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> productService.createProduct(dtoWithId)
        );
        assertTrue(dtoEx.getMessage().contains("ID must not be provided"));

        InvalidQualityOperationException entityEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> productService.createProduct(sampleProduct)
        );
        assertTrue(entityEx.getMessage().contains("ID must not be provided"));

        verify(productRepository, never()).existsByProductCode(any());
        verify(productRepository, never()).saveAndFlush(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should normalize and record authenticated actor username during creation")
    void shouldCreateProductWithExplicitActorName() {
        authenticateAs("  Dr.Patel  ", "ADMIN");
        Product transientProduct = new Product(
                "PRD-001", "Paracetamol", "500 mg", "Tablet", "Analgesic medication", ProductStatus.ACTIVE);
        when(productRepository.existsByProductCode("PRD-001")).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenReturn(sampleProduct);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        productService.createProduct(transientProduct);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertEquals("dr.patel", captor.getValue().getActorName());
    }

    @Test
    @DisplayName("Should reject product creation when product code already exists without writing audit")
    void shouldRejectDuplicateProductCodeOnCreate() {
        Product transientProduct = new Product(
                "PRD-001", "Paracetamol", "500 mg", "Tablet", "Analgesic medication", ProductStatus.ACTIVE);
        when(productRepository.existsByProductCode("PRD-001")).thenReturn(true);

        DuplicateResourceException exception = assertThrows(
                DuplicateResourceException.class,
                () -> productService.createProduct(transientProduct)
        );

        assertTrue(exception.getMessage().contains("PRD-001"));
        verify(productRepository, times(1)).existsByProductCode("PRD-001");
        verify(productRepository, never()).saveAndFlush(any(Product.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should fail closed when unauthenticated or when authenticated actor exceeds 100 characters without saving product or audit")
    void shouldRejectOversizedActorNameOnCreate() {
        Product transientProduct = new Product(
                "PRD-001", "Paracetamol", "500 mg", "Tablet", "Analgesic medication", ProductStatus.ACTIVE);

        SecurityContextHolder.clearContext();
        assertThrows(
                AuthenticationCredentialsNotFoundException.class,
                () -> productService.createProduct(transientProduct)
        );

        authenticateAs("A".repeat(101), "ADMIN");
        assertThrows(
                InsufficientAuthenticationException.class,
                () -> productService.createProduct(transientProduct)
        );

        verify(productRepository, never()).saveAndFlush(any(Product.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should retrieve all products")
    void shouldRetrieveAllProducts() {
        Product product2 = new Product("PRD-002", "Amoxicillin", "250 mg", "Capsule", null, ProductStatus.ACTIVE);
        product2.setId(2L);

        when(productRepository.findAll()).thenReturn(List.of(sampleProduct, product2));

        List<Product> products = productService.getAllProducts();

        assertEquals(2, products.size());
        verify(productRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("Should retrieve product by existing ID")
    void shouldRetrieveProductByExistingId() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));

        Product found = productService.getProductById(1L);

        assertNotNull(found);
        assertEquals(1L, found.getId());
        assertEquals("Paracetamol", found.getProductName());
        verify(productRepository, times(1)).findById(1L);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when product ID does not exist")
    void shouldThrowExceptionWhenProductIdNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> productService.getProductById(999L)
        );

        assertTrue(exception.getMessage().contains("999"));
        verify(productRepository, times(1)).findById(999L);
    }

    @Test
    @DisplayName("Should update product retaining own code and record PRODUCT_UPDATED with before/after snapshots")
    void shouldUpdateProductSuccessfullyRetainingOwnCode() throws Exception {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        Product updatePayload = new Product(
                "PRD-001", // same code
                "Paracetamol Extra",
                "650 mg",
                "Tablet",
                "Enhanced formulation",
                ProductStatus.ACTIVE
        );

        authenticateAs("qa.specialist", "ADMIN");
        Product updated = productService.updateProduct(1L, updatePayload);

        assertEquals("PRD-001", updated.getProductCode());
        assertEquals("Paracetamol Extra", updated.getProductName());
        assertEquals("650 mg", updated.getStrength());
        assertEquals("Enhanced formulation", updated.getDescription());
        verify(productRepository, never()).existsByProductCode("PRD-001");
        verify(productRepository, times(1)).saveAndFlush(sampleProduct);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("PRODUCT", audit.getEntityType());
        assertEquals(1L, audit.getEntityId());
        assertEquals("PRD-001", audit.getEntityReference());
        assertNull(audit.getBatchId());
        assertEquals("PRODUCT_UPDATED", audit.getAction());
        assertEquals("qa.specialist", audit.getActorName());

        JsonNode before = objectMapper.readTree(audit.getBeforeValues());
        JsonNode after = objectMapper.readTree(audit.getAfterValues());
        assertEquals("Paracetamol", before.get("productName").asText());
        assertEquals("500 mg", before.get("strength").asText());
        assertEquals("Analgesic medication", before.get("description").asText());
        assertEquals("Paracetamol Extra", after.get("productName").asText());
        assertEquals("650 mg", after.get("strength").asText());
        assertEquals("Enhanced formulation", after.get("description").asText());
    }

    @Test
    @DisplayName("Should update product code to new unique code and reflect code change in before/after snapshots")
    void shouldUpdateProductCodeToNewUniqueCode() throws Exception {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(productRepository.existsByProductCode("PRD-001-NEW")).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        Product updatePayload = new Product(
                "PRD-001-NEW",
                "Paracetamol",
                "500 mg",
                "Tablet",
                null,
                ProductStatus.ACTIVE
        );

        Product updated = productService.updateProduct(1L, updatePayload);

        assertEquals("PRD-001-NEW", updated.getProductCode());
        verify(productRepository, times(1)).existsByProductCode("PRD-001-NEW");
        verify(productRepository, times(1)).saveAndFlush(sampleProduct);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("PRODUCT_UPDATED", audit.getAction());
        assertEquals("PRD-001-NEW", audit.getEntityReference());
        assertEquals("system.admin", audit.getActorName());

        JsonNode before = objectMapper.readTree(audit.getBeforeValues());
        JsonNode after = objectMapper.readTree(audit.getAfterValues());
        assertEquals("PRD-001", before.get("productCode").asText());
        assertEquals("PRD-001-NEW", after.get("productCode").asText());
        assertTrue(after.get("description").isNull());
    }

    @Test
    @DisplayName("Should emit PRODUCT_UPDATED and PRODUCT_DEACTIVATED when PUT updates details and status together")
    void shouldRecordUpdateAndDeactivationWhenPutChangesBothDetailsAndStatus() throws Exception {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        Product updatePayload = new Product(
                "PRD-001",
                "Paracetamol Discontinued",
                "500 mg",
                "Tablet",
                "Phased out",
                ProductStatus.INACTIVE
        );

        authenticateAs("Master.Data.Admin", "ADMIN");
        Product updated = productService.updateProduct(1L, updatePayload);

        assertEquals(ProductStatus.INACTIVE, updated.getStatus());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(captor.capture());
        List<AuditLog> entries = captor.getAllValues();

        AuditLog editAudit = entries.get(0);
        assertEquals("PRODUCT_UPDATED", editAudit.getAction());
        assertEquals("master.data.admin", editAudit.getActorName());
        assertEquals("Paracetamol", objectMapper.readTree(editAudit.getBeforeValues()).get("productName").asText());
        assertEquals("ACTIVE", objectMapper.readTree(editAudit.getBeforeValues()).get("status").asText());
        assertEquals("Paracetamol Discontinued", objectMapper.readTree(editAudit.getAfterValues()).get("productName").asText());
        assertEquals("ACTIVE", objectMapper.readTree(editAudit.getAfterValues()).get("status").asText());

        AuditLog deactivateAudit = entries.get(1);
        assertEquals("PRODUCT_DEACTIVATED", deactivateAudit.getAction());
        assertEquals("master.data.admin", deactivateAudit.getActorName());
        assertEquals("ACTIVE", objectMapper.readTree(deactivateAudit.getBeforeValues()).get("status").asText());
        assertEquals("INACTIVE", objectMapper.readTree(deactivateAudit.getAfterValues()).get("status").asText());
    }

    @Test
    @DisplayName("Should emit PRODUCT_DEACTIVATED without PRODUCT_UPDATED when PUT changes only status")
    void shouldRecordStatusActionOnlyWhenPutChangesOnlyStatus() throws Exception {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        Product statusOnlyPayload = new Product(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Analgesic medication",
                ProductStatus.INACTIVE
        );

        authenticateAs("qa.director", "ADMIN");
        productService.updateProduct(1L, statusOnlyPayload);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("PRODUCT_DEACTIVATED", audit.getAction());
        assertEquals("qa.director", audit.getActorName());
        assertEquals("ACTIVE", objectMapper.readTree(audit.getBeforeValues()).get("status").asText());
        assertEquals("INACTIVE", objectMapper.readTree(audit.getAfterValues()).get("status").asText());
    }

    @Test
    @DisplayName("Should skip persistence and audit logging when update payload causes no field changes")
    void shouldSkipAuditWhenUpdateChangesNothing() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));

        Product identicalPayload = new Product(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Analgesic medication",
                ProductStatus.ACTIVE
        );

        Product result = productService.updateProduct(1L, identicalPayload);

        assertEquals("PRD-001", result.getProductCode());
        verify(productRepository, never()).saveAndFlush(any(Product.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject product update when new product code belongs to another product without writing audit")
    void shouldRejectDuplicateProductCodeOnUpdate() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(productRepository.existsByProductCode("PRD-TAKEN")).thenReturn(true);

        Product updatePayload = new Product(
                "PRD-TAKEN",
                "Paracetamol",
                "500 mg",
                "Tablet",
                null,
                ProductStatus.ACTIVE
        );

        DuplicateResourceException exception = assertThrows(
                DuplicateResourceException.class,
                () -> productService.updateProduct(1L, updatePayload)
        );

        assertTrue(exception.getMessage().contains("PRD-TAKEN"));
        verify(productRepository, times(1)).existsByProductCode("PRD-TAKEN");
        verify(productRepository, never()).saveAndFlush(any(Product.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when updating a non-existent product without writing audit")
    void shouldThrowExceptionWhenUpdatingNonExistentProduct() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        Product updatePayload = new Product("PRD-001", "Paracetamol", "500 mg", "Tablet", null, ProductStatus.ACTIVE);

        assertThrows(
                ResourceNotFoundException.class,
                () -> productService.updateProduct(999L, updatePayload)
        );

        verify(productRepository, never()).saveAndFlush(any(Product.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should record PRODUCT_DEACTIVATED and PRODUCT_ACTIVATED with before/after status snapshots")
    void shouldUpdateProductStatusSuccessfullyAndRecordAudit() throws Exception {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Deactivate ACTIVE -> INACTIVE
        authenticateAs("QA.Lead", "ADMIN");
        Product deactivated = productService.updateProductStatus(1L, ProductStatus.INACTIVE);
        assertEquals(ProductStatus.INACTIVE, deactivated.getStatus());

        // 2. Reactivate INACTIVE -> ACTIVE
        authenticateAs("system.admin", "ADMIN");
        Product reactivated = productService.updateProductStatus(1L, ProductStatus.ACTIVE);
        assertEquals(ProductStatus.ACTIVE, reactivated.getStatus());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(captor.capture());
        List<AuditLog> entries = captor.getAllValues();

        AuditLog deactivateAudit = entries.get(0);
        assertEquals("PRODUCT", deactivateAudit.getEntityType());
        assertEquals(1L, deactivateAudit.getEntityId());
        assertEquals("PRD-001", deactivateAudit.getEntityReference());
        assertNull(deactivateAudit.getBatchId());
        assertEquals("PRODUCT_DEACTIVATED", deactivateAudit.getAction());
        assertEquals("qa.lead", deactivateAudit.getActorName());
        assertEquals("ACTIVE", objectMapper.readTree(deactivateAudit.getBeforeValues()).get("status").asText());
        assertEquals("INACTIVE", objectMapper.readTree(deactivateAudit.getAfterValues()).get("status").asText());

        AuditLog activateAudit = entries.get(1);
        assertEquals("PRODUCT_ACTIVATED", activateAudit.getAction());
        assertEquals("system.admin", activateAudit.getActorName());
        assertEquals("INACTIVE", objectMapper.readTree(activateAudit.getBeforeValues()).get("status").asText());
        assertEquals("ACTIVE", objectMapper.readTree(activateAudit.getAfterValues()).get("status").asText());
    }

    @Test
    @DisplayName("Should skip persistence and audit logging when target status already matches current status")
    void shouldSkipAuditWhenStatusAlreadyMatches() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));

        Product result = productService.updateProductStatus(1L, ProductStatus.ACTIVE);

        assertEquals(ProductStatus.ACTIVE, result.getStatus());
        verify(productRepository, never()).saveAndFlush(any(Product.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when updating status for missing product without writing audit")
    void shouldThrowExceptionWhenUpdatingStatusForMissingProduct() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> productService.updateProductStatus(999L, ProductStatus.INACTIVE)
        );

        verify(productRepository, times(1)).findById(999L);
        verify(productRepository, never()).saveAndFlush(any(Product.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject null target status without touching repository or audit trail")
    void shouldRejectNullTargetStatus() {
        assertThrows(
                InvalidQualityOperationException.class,
                () -> productService.updateProductStatus(1L, null)
        );

        verify(productRepository, never()).saveAndFlush(any(Product.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }
}

