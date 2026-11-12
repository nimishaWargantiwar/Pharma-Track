package com.pharmatrack.service;

import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.InvalidQualityOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service implementation for Pharmaceutical Product management
 * with transactional append-only audit trail recording attributed to the
 * authenticated SecurityContext user.
 */
@Service
@Transactional
public class ProductService {

    public static final String ENTITY_PRODUCT = "PRODUCT";
    public static final ZoneId APP_ZONE_ID = AppTime.APP_ZONE_ID;

    private final ProductRepository productRepository;
    private final AuditTrailService auditTrailService;
    private final AuthenticatedActorResolver authenticatedActorResolver;

    public ProductService(ProductRepository productRepository, AuditTrailService auditTrailService) {
        this(productRepository, auditTrailService, new AuthenticatedActorResolver());
    }

    @Autowired
    public ProductService(ProductRepository productRepository,
                          AuditTrailService auditTrailService,
                          AuthenticatedActorResolver authenticatedActorResolver) {
        this.productRepository = productRepository;
        this.auditTrailService = auditTrailService;
        this.authenticatedActorResolver = authenticatedActorResolver != null
                ? authenticatedActorResolver
                : new AuthenticatedActorResolver();
    }

    /**
     * Creates a new product from a dedicated creation request DTO and records an append-only
     * {@code PRODUCT_CREATED} audit entry attributed to the authenticated user.
     *
     * @param requestDto the creation request payload
     * @return the persisted product
     */
    public Product createProduct(ProductCreateRequestDto requestDto) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidQualityOperationException("Product payload cannot be null");
        }
        if (requestDto.getId() != null) {
            throw new InvalidQualityOperationException("Product ID must not be provided when creating a product");
        }
        Product transientProduct = new Product(
                requestDto.getProductCode(),
                requestDto.getProductName(),
                requestDto.getStrength(),
                requestDto.getDosageForm(),
                requestDto.getDescription(),
                requestDto.getStatus()
        );
        return createProductInternal(transientProduct, resolvedActor);
    }

    /**
     * Creates a new product and records an append-only {@code PRODUCT_CREATED} audit entry
     * attributed to the authenticated user.
     * Prevents duplicate product codes from being registered.
     *
     * @param product the product entity to create
     * @return the persisted product
     * @throws DuplicateResourceException if the product code already exists
     */
    public Product createProduct(Product product) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        return createProductInternal(product, resolvedActor);
    }

    private Product createProductInternal(Product product, String resolvedActor) {
        if (product == null) {
            throw new InvalidQualityOperationException("Product payload cannot be null");
        }
        if (product.getId() != null) {
            throw new InvalidQualityOperationException("Product ID must not be provided when creating a product");
        }

        if (product.getProductCode() != null && productRepository.existsByProductCode(product.getProductCode())) {
            throw new DuplicateResourceException("Product with code '" + product.getProductCode() + "' already exists");
        }

        Product savedProduct = productRepository.saveAndFlush(product);
        Long entityId = savedProduct.getId();
        Map<String, Object> afterSnapshot = buildProductSnapshot(savedProduct);

        auditTrailService.recordAudit(
                ENTITY_PRODUCT,
                entityId,
                savedProduct.getProductCode(),
                null,
                "PRODUCT_CREATED",
                resolvedActor,
                AppTime.nowDateTime(),
                "Created product " + savedProduct.getProductCode(),
                null,
                afterSnapshot
        );

        return savedProduct;
    }

    /**
     * Retrieves all products registered in the system.
     *
     * @return list of all products
     */
    @Transactional(readOnly = true)
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    /**
     * Retrieves a single product by its unique database ID.
     *
     * @param id the primary key of the product
     * @return the found product
     * @throws ResourceNotFoundException if no product exists with the given ID
     */
    @Transactional(readOnly = true)
    public Product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
    }

    /**
     * Updates an existing product and records append-only audit entries with before/after snapshots
     * attributed to the authenticated user.
     * Allows the existing product to retain its own product code, but prevents
     * taking another product's product code.
     *
     * @param id             the ID of the product to update
     * @param updatedProduct the updated product payload
     * @return the updated product
     * @throws ResourceNotFoundException  if the product is not found
     * @throws DuplicateResourceException if the new product code is already in use by another product
     */
    public Product updateProduct(Long id, Product updatedProduct) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (updatedProduct == null) {
            throw new InvalidQualityOperationException("Product payload cannot be null");
        }

        Product existingProduct = getProductById(id);

        String newCode = updatedProduct.getProductCode();
        boolean updateCode = newCode != null && !newCode.equalsIgnoreCase(existingProduct.getProductCode());
        if (updateCode && productRepository.existsByProductCode(newCode)) {
            throw new DuplicateResourceException("Product with code '" + newCode + "' already exists");
        }

        Map<String, Object> beforeSnapshot = buildProductSnapshot(existingProduct);
        ProductStatus previousStatus = existingProduct.getStatus();

        if (updateCode) {
            existingProduct.setProductCode(newCode);
        }
        existingProduct.setProductName(updatedProduct.getProductName());
        existingProduct.setStrength(updatedProduct.getStrength());
        existingProduct.setDosageForm(updatedProduct.getDosageForm());
        existingProduct.setDescription(updatedProduct.getDescription());
        if (updatedProduct.getStatus() != null) {
            existingProduct.setStatus(updatedProduct.getStatus());
        }

        Map<String, Object> candidateAfterSnapshot = buildProductSnapshot(existingProduct);
        if (beforeSnapshot.equals(candidateAfterSnapshot)) {
            return existingProduct;
        }

        Product savedProduct = productRepository.saveAndFlush(existingProduct);
        Map<String, Object> afterSnapshot = buildProductSnapshot(savedProduct);
        ProductStatus newStatus = savedProduct.getStatus();

        boolean statusChanged = newStatus != null && previousStatus != newStatus;
        boolean detailsChanged = hasNonStatusChanges(beforeSnapshot, afterSnapshot);
        LocalDateTime occurredAt = AppTime.nowDateTime();

        if (detailsChanged && statusChanged) {
            Map<String, Object> intermediateSnapshot = new LinkedHashMap<>(afterSnapshot);
            intermediateSnapshot.put("status", previousStatus != null ? previousStatus.name() : null);

            auditTrailService.recordAudit(
                    ENTITY_PRODUCT,
                    savedProduct.getId(),
                    savedProduct.getProductCode(),
                    null,
                    "PRODUCT_UPDATED",
                    resolvedActor,
                    occurredAt,
                    "Updated product " + savedProduct.getProductCode(),
                    beforeSnapshot,
                    intermediateSnapshot
            );

            String statusAction = resolveStatusAction(newStatus);
            auditTrailService.recordAudit(
                    ENTITY_PRODUCT,
                    savedProduct.getId(),
                    savedProduct.getProductCode(),
                    null,
                    statusAction,
                    resolvedActor,
                    occurredAt,
                    buildStatusReason(newStatus, savedProduct.getProductCode()),
                    intermediateSnapshot,
                    afterSnapshot
            );
        } else if (statusChanged) {
            String statusAction = resolveStatusAction(newStatus);
            auditTrailService.recordAudit(
                    ENTITY_PRODUCT,
                    savedProduct.getId(),
                    savedProduct.getProductCode(),
                    null,
                    statusAction,
                    resolvedActor,
                    occurredAt,
                    buildStatusReason(newStatus, savedProduct.getProductCode()),
                    beforeSnapshot,
                    afterSnapshot
            );
        } else {
            auditTrailService.recordAudit(
                    ENTITY_PRODUCT,
                    savedProduct.getId(),
                    savedProduct.getProductCode(),
                    null,
                    "PRODUCT_UPDATED",
                    resolvedActor,
                    occurredAt,
                    "Updated product " + savedProduct.getProductCode(),
                    beforeSnapshot,
                    afterSnapshot
            );
        }

        return savedProduct;
    }

    /**
     * Updates the lifecycle status of an existing product and records an append-only
     * {@code PRODUCT_ACTIVATED} or {@code PRODUCT_DEACTIVATED} audit entry attributed
     * to the authenticated user.
     *
     * @param id     the ID of the product
     * @param status the new lifecycle status (ACTIVE, INACTIVE)
     * @return the updated product
     * @throws ResourceNotFoundException if the product does not exist
     */
    public Product updateProductStatus(Long id, ProductStatus status) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (status == null) {
            throw new InvalidQualityOperationException("Product status is mandatory (ACTIVE, INACTIVE)");
        }

        Product existingProduct = getProductById(id);
        ProductStatus previousStatus = existingProduct.getStatus();
        if (previousStatus == status) {
            return existingProduct;
        }

        Map<String, Object> beforeSnapshot = buildProductSnapshot(existingProduct);
        existingProduct.setStatus(status);

        Product savedProduct = productRepository.saveAndFlush(existingProduct);
        Map<String, Object> afterSnapshot = buildProductSnapshot(savedProduct);

        auditTrailService.recordAudit(
                ENTITY_PRODUCT,
                savedProduct.getId(),
                savedProduct.getProductCode(),
                null,
                resolveStatusAction(status),
                resolvedActor,
                AppTime.nowDateTime(),
                buildStatusReason(status, savedProduct.getProductCode()),
                beforeSnapshot,
                afterSnapshot
        );

        return savedProduct;
    }

    private Map<String, Object> buildProductSnapshot(Product product) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("productCode", product.getProductCode());
        snapshot.put("productName", product.getProductName());
        snapshot.put("strength", product.getStrength());
        snapshot.put("dosageForm", product.getDosageForm());
        snapshot.put("description", product.getDescription());
        snapshot.put("status", product.getStatus() != null ? product.getStatus().name() : null);
        return snapshot;
    }

    private boolean hasNonStatusChanges(Map<String, Object> before, Map<String, Object> after) {
        return !Objects.equals(before.get("productCode"), after.get("productCode"))
                || !Objects.equals(before.get("productName"), after.get("productName"))
                || !Objects.equals(before.get("strength"), after.get("strength"))
                || !Objects.equals(before.get("dosageForm"), after.get("dosageForm"))
                || !Objects.equals(before.get("description"), after.get("description"));
    }

    private String resolveStatusAction(ProductStatus status) {
        return status == ProductStatus.ACTIVE ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED";
    }

    private String buildStatusReason(ProductStatus status, String productCode) {
        return (status == ProductStatus.ACTIVE ? "Activated product " : "Deactivated product ") + productCode;
    }
}

