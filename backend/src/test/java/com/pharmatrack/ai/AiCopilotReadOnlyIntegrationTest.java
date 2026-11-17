package com.pharmatrack.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.client.LlmClient;
import com.pharmatrack.ai.client.LlmResponse;
import com.pharmatrack.ai.config.AiCopilotProperties;
import com.pharmatrack.dto.AiQueryRequestDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderResponseDto;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeResponseDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.service.ManufacturingOrderService;
import com.pharmatrack.service.ProductService;
import com.pharmatrack.service.RecipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AI Copilot Read-Only Invariance Integration Tests")
class AiCopilotReadOnlyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ManufacturingOrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private ManufacturingOrderRepository orderRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AiCopilotProperties aiProperties;

    @MockBean
    private LlmClient llmClient;

    private String testOrderNumber;

    @BeforeEach
    @WithMockUser(username = "admin_user", roles = {"ADMIN", "PRODUCTION_SUPERVISOR"})
    void setUp() {
        aiProperties.setEnabled(true);
        aiProperties.setApiKey("sk-integration-test-key");

        // Create test product and recipe if needed
        String pCode = "PROD-AI-" + System.currentTimeMillis();
        Product product = productService.createProduct(new ProductCreateRequestDto(
                pCode,
                "AI Test Product",
                "500mg",
                "Tablets",
                "Test description",
                ProductStatus.ACTIVE
        ));

        RecipeResponseDto createdRecipe = recipeService.createRecipe(new RecipeRequestDto(
                product.getId(),
                new BigDecimal("10000.00"),
                "TABLETS",
                "Recipe for AI testing",
                List.of(new RecipeMaterialDto("Active Ingredient", new BigDecimal("50.00"), "KG")),
                List.of(new RecipeStepDto(1, "Blending", "Blend raw materials"))
        ));

        recipeService.updateRecipeStatus(createdRecipe.getId(), new RecipeStatusUpdateDto(RecipeStatus.APPROVED));

        // Create manufacturing order
        OrderResponseDto createdOrder = orderService.createOrder(new OrderRequestDto(
                product.getId(),
                createdRecipe.getId(),
                new BigDecimal("20000.00"),
                "TABLETS",
                LocalDate.now(),
                LocalDate.now().plusDays(5),
                "Order created for read-only AI Copilot verification"
        ));
        testOrderNumber = createdOrder.getOrderNumber();
    }

    @Test
    @DisplayName("AI Copilot queries do NOT modify database records, batch states, or create audit logs")
    @WithMockUser(username = "operator_sam", roles = "OPERATOR")
    void query_preservesCompleteDatabaseInvariance() throws Exception {
        // Mock LLM response
        when(llmClient.generate(anyString(), anyString())).thenReturn(new LlmResponse(
                "Order " + testOrderNumber + " is in PLANNED status with 2 batches.",
                "gpt-6-luna",
                "resp_ro_123",
                100,
                25,
                125
        ));

        // 1. Capture database baseline before AI invocation
        long auditLogCountBefore = auditLogRepository.count();
        ManufacturingOrder orderBefore = orderRepository.findByOrderNumber(testOrderNumber).orElseThrow();
        OrderStatus statusBefore = orderBefore.getStatus();
        LocalDateTime orderUpdatedBefore = orderBefore.getUpdatedAt();

        List<Batch> batchesBefore = batchRepository.findByOrderId(orderBefore.getId());
        assertThat(batchesBefore).isNotEmpty();
        BatchStatus batchStatusBefore = batchesBefore.get(0).getStatus();
        LocalDateTime batchUpdatedBefore = batchesBefore.get(0).getUpdatedAt();

        // 2. Perform AI Copilot query
        AiQueryRequestDto request = new AiQueryRequestDto("What is the status of " + testOrderNumber + "?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Order " + testOrderNumber + " is in PLANNED status with 2 batches."))
                .andExpect(jsonPath("$.contextIdentifier").value(testOrderNumber))
                .andExpect(jsonPath("$.sources").isArray());

        // 3. Verify complete database invariance after AI invocation
        long auditLogCountAfter = auditLogRepository.count();
        assertThat(auditLogCountAfter)
                .as("AI queries must NEVER create audit log entries")
                .isEqualTo(auditLogCountBefore);

        ManufacturingOrder orderAfter = orderRepository.findByOrderNumber(testOrderNumber).orElseThrow();
        assertThat(orderAfter.getStatus())
                .as("Manufacturing order status must not change")
                .isEqualTo(statusBefore);
        assertThat(orderAfter.getUpdatedAt())
                .as("Manufacturing order updated_at timestamp must not change")
                .isEqualTo(orderUpdatedBefore);

        List<Batch> batchesAfter = batchRepository.findByOrderId(orderAfter.getId());
        assertThat(batchesAfter.get(0).getStatus())
                .as("Child batch status must not change")
                .isEqualTo(batchStatusBefore);
        assertThat(batchesAfter.get(0).getUpdatedAt())
                .as("Child batch updated_at timestamp must not change")
                .isEqualTo(batchUpdatedBefore);
    }
}
