package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.controller.ProductController;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.dto.StatusUpdateRequestDto;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.security.SecurityConfig;
import com.pharmatrack.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@WithMockUser(username = "admin.user", roles = "ADMIN")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService productService;

    private Product sampleProduct;
    private ProductCreateRequestDto sampleCreateRequest;

    @BeforeEach
    void setUp() {
        sampleProduct = new Product(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Pain reliever",
                ProductStatus.ACTIVE
        );
        sampleProduct.setId(1L);

        sampleCreateRequest = new ProductCreateRequestDto(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Pain reliever",
                ProductStatus.ACTIVE
        );
    }

    @Test
    @DisplayName("POST /api/products - Should create product and return 201 Created")
    void createProduct_Success_Returns201() throws Exception {
        when(productService.createProduct(any(ProductCreateRequestDto.class))).thenReturn(sampleProduct);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleCreateRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productCode").value("PRD-001"))
                .andExpect(jsonPath("$.productName").value("Paracetamol"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /api/products - Should reject caller-supplied ID with 400 Bad Request")
    void createProduct_WithSuppliedId_Returns400() throws Exception {
        ProductCreateRequestDto hijackAttempt = new ProductCreateRequestDto(
                1L,
                "PRD-HIJACK",
                "Overwritten Product",
                "500 mg",
                "Tablet",
                "Malicious merge attempt",
                ProductStatus.INACTIVE
        );

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hijackAttempt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.id").value(containsString("must not be provided")));

        verify(productService, never()).createProduct(any(ProductCreateRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/products - Should return 400 Bad Request when validation fails")
    void createProduct_InvalidRequest_Returns400() throws Exception {
        ProductCreateRequestDto invalidProduct = new ProductCreateRequestDto();
        // Mandatory fields left null/blank

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidProduct)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.productCode").exists())
                .andExpect(jsonPath("$.validationErrors.productName").exists());
    }

    @Test
    @DisplayName("GET /api/products - Should retrieve all products and return 200 OK")
    void getAllProducts_Returns200() throws Exception {
        Product product2 = new Product("PRD-002", "Amoxicillin", "250 mg", "Capsule", null, ProductStatus.ACTIVE);
        product2.setId(2L);

        when(productService.getAllProducts()).thenReturn(List.of(sampleProduct, product2));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].productCode").value("PRD-001"))
                .andExpect(jsonPath("$[1].productCode").value("PRD-002"));
    }

    @Test
    @DisplayName("GET /api/products/{id} - Should retrieve product by ID and return 200 OK")
    void getProductById_Success_Returns200() throws Exception {
        when(productService.getProductById(1L)).thenReturn(sampleProduct);

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productCode").value("PRD-001"))
                .andExpect(jsonPath("$.productName").value("Paracetamol"));
    }

    @Test
    @DisplayName("GET /api/products/{id} - Missing product should return 404 Not Found")
    void getProductById_NotFound_Returns404() throws Exception {
        when(productService.getProductById(999L))
                .thenThrow(new ResourceNotFoundException("Product not found with id: 999"));

        mockMvc.perform(get("/api/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Product not found with id: 999"));
    }

    @Test
    @DisplayName("PUT /api/products/{id} - Should update product details and return 200 OK")
    void updateProduct_Success_Returns200() throws Exception {
        Product updatedProduct = new Product(
                "PRD-001",
                "Paracetamol Extra",
                "650 mg",
                "Tablet",
                "Enhanced",
                ProductStatus.ACTIVE
        );
        updatedProduct.setId(1L);

        when(productService.updateProduct(eq(1L), any(Product.class))).thenReturn(updatedProduct);

        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedProduct)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productName").value("Paracetamol Extra"))
                .andExpect(jsonPath("$.strength").value("650 mg"));
    }

    @Test
    @DisplayName("PUT /api/products/{id} - Duplicate product code should return 409 Conflict")
    void updateProduct_DuplicateProductCode_Returns409() throws Exception {
        Product updatePayload = new Product(
                "PRD-TAKEN",
                "Paracetamol",
                "500 mg",
                "Tablet",
                null,
                ProductStatus.ACTIVE
        );

        when(productService.updateProduct(eq(1L), any(Product.class)))
                .thenThrow(new DuplicateResourceException("Product with code 'PRD-TAKEN' already exists"));

        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Product with code 'PRD-TAKEN' already exists"));
    }

    @Test
    @DisplayName("PATCH /api/products/{id}/status - Should update status and return 200 OK")
    void updateProductStatus_Success_Returns200() throws Exception {
        Product inactiveProduct = new Product(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Pain reliever",
                ProductStatus.INACTIVE
        );
        inactiveProduct.setId(1L);

        when(productService.updateProductStatus(1L, ProductStatus.INACTIVE)).thenReturn(inactiveProduct);

        StatusUpdateRequestDto statusDto = new StatusUpdateRequestDto(ProductStatus.INACTIVE);

        mockMvc.perform(patch("/api/products/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    @DisplayName("PATCH /api/products/{id}/status - Invalid request body should return 400 Bad Request")
    void updateProductStatus_InvalidRequest_Returns400() throws Exception {
        mockMvc.perform(patch("/api/products/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("Forged X-Actor-Name header and JSON actorName are ignored cleanly; non-ADMIN and anonymous mutations are rejected")
    void forgedActorIgnoredAndRbacEnforcedOnProductMutations() throws Exception {
        when(productService.createProduct(any(ProductCreateRequestDto.class))).thenReturn(sampleProduct);
        mockMvc.perform(post("/api/products")
                        .header("X-Actor-Name", "Forged Director")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleCreateRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productCode").value("PRD-001"));
        verify(productService).createProduct(any(ProductCreateRequestDto.class));

        when(productService.updateProduct(eq(1L), any(Product.class))).thenReturn(sampleProduct);
        mockMvc.perform(put("/api/products/1")
                        .header("X-Actor-Name", "Forged Editor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleProduct)))
                .andExpect(status().isOk());
        verify(productService).updateProduct(eq(1L), any(Product.class));

        Product inactiveProduct = new Product(
                "PRD-001", "Paracetamol", "500 mg", "Tablet", "Pain reliever", ProductStatus.INACTIVE);
        inactiveProduct.setId(1L);
        when(productService.updateProductStatus(1L, ProductStatus.INACTIVE)).thenReturn(inactiveProduct);

        mockMvc.perform(patch("/api/products/1/status")
                        .header("X-Actor-Name", "Forged Approver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"actorName\":\"Forged JSON Actor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        verify(productService).updateProductStatus(1L, ProductStatus.INACTIVE);
    }

    @Test
    @WithMockUser(username = "operator.user", roles = "OPERATOR")
    @DisplayName("OPERATOR can read products but receives 403 Forbidden on create/update/status mutations")
    void operatorAllowedReadButForbiddenMutations() throws Exception {
        when(productService.getAllProducts()).thenReturn(List.of(sampleProduct));
        mockMvc.perform(get("/api/products")).andExpect(status().isOk());

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleCreateRequest)))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleProduct)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/products/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StatusUpdateRequestDto(ProductStatus.INACTIVE))))
                .andExpect(status().isForbidden());
        verify(productService, never()).updateProductStatus(any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user receives 401 Unauthorized on product endpoints")
    void anonymousProductRequestsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleCreateRequest)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(productService);
    }
}

