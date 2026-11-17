package com.pharmatrack.ai.service;

import com.pharmatrack.ai.client.LlmClient;
import com.pharmatrack.ai.client.LlmClientException;
import com.pharmatrack.ai.client.LlmResponse;
import com.pharmatrack.ai.config.AiCopilotProperties;
import com.pharmatrack.dto.AiQueryRequestDto;
import com.pharmatrack.dto.AiQueryResponseDto;
import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.OrderResponseDto;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.service.ManufacturingOrderService;
import com.pharmatrack.dto.BatchTraceabilityDossierDto;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.service.BatchTraceabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiCopilotService Unit Tests")
class AiCopilotServiceTest {

    @Mock
    private ManufacturingOrderService manufacturingOrderService;

    @Mock
    private LlmClient llmClient;

    @Mock
    private AuthenticatedActorResolver authenticatedActorResolver;

    @Mock
    private BatchTraceabilityService batchTraceabilityService;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Spy
    private BatchContextBuilder batchContextBuilder = new BatchContextBuilder();

    private AiCopilotProperties properties;
    private OrderContextBuilder orderContextBuilder;
    private AiCopilotService service;

    @BeforeEach
    void setUp() {
        properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-test-key-12345");
        properties.setModel("gpt-6-luna");

        orderContextBuilder = new OrderContextBuilder();
        service = new AiCopilotService(
                manufacturingOrderService,
                orderContextBuilder,
                batchTraceabilityService,
                batchContextBuilder,
                llmClient,
                properties,
                authenticatedActorResolver,
                userAccountRepository
        );

        lenient().when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_jane");
        lenient().when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR")).thenReturn(true);
    }

    private OrderResponseDto createMockOrderDto(String orderNumber) {
        String batchNumber = "BAT-" + orderNumber.replace("MO-", "") + "-01";
        BatchResponseDto batch = new BatchResponseDto(
                101L,
                batchNumber,
                1L,
                orderNumber,
                new BigDecimal("50000.00"),
                "TABLETS",
                new BigDecimal("49800.00"),
                BatchStatus.COMPLETED,
                BatchQualityStatus.RELEASED,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2028, 3, 1),
                LocalDateTime.of(2026, 3, 1, 8, 0),
                LocalDateTime.of(2026, 3, 2, 16, 0),
                LocalDateTime.of(2026, 3, 1, 7, 0),
                LocalDateTime.of(2026, 3, 2, 16, 0)
        );

        return new OrderResponseDto(
                1L,
                orderNumber,
                10L,
                "PROD-ASP-500",
                "Aspirin 500mg Tablets",
                20L,
                "REC-ASP-001",
                1,
                new BigDecimal("50000.00"),
                "TABLETS",
                1,
                OrderStatus.COMPLETED,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 2),
                LocalDateTime.of(2026, 3, 1, 8, 0),
                LocalDateTime.of(2026, 3, 2, 16, 0),
                "Internal manufacturing note",
                List.of(batch),
                LocalDateTime.of(2026, 2, 28, 9, 0),
                LocalDateTime.of(2026, 3, 2, 16, 0)
        );
    }

    @Test
    @DisplayName("Valid order number in query fetches order, builds context, and invokes LLM")
    void query_validOrderNumber_fetchesOrderAndReturnsLlmResponse() {
        String orderNumber = "MO-2026-0001";
        OrderResponseDto mockOrder = createMockOrderDto(orderNumber);

        when(manufacturingOrderService.getOrderByOrderNumber(orderNumber)).thenReturn(mockOrder);
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse(
                        "Manufacturing order MO-2026-0001 is COMPLETED with 1 batch (BAT-2026-0001-01) having passed QA disposition.",
                        "gpt-6-luna",
                        "resp_test_123",
                        120,
                        35,
                        155
                )
        );

        AiQueryRequestDto request = new AiQueryRequestDto("What is the status of manufacturing order MO-2026-0001?");
        AiQueryResponseDto response = service.query(request);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).contains("MO-2026-0001 is COMPLETED");
        assertThat(response.getContextType()).isEqualTo("MANUFACTURING_ORDER");
        assertThat(response.getContextIdentifier()).isEqualTo("MO-2026-0001");
        assertThat(response.getModel()).isEqualTo("gpt-6-luna");
        assertThat(response.getSources()).hasSize(2); // 1 order + 1 batch

        ArgumentCaptor<String> instructionsCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(instructionsCaptor.capture(), inputCaptor.capture());

        assertThat(instructionsCaptor.getValue()).contains("You are PharmaTrack AI Copilot");
        assertThat(instructionsCaptor.getValue()).contains("<<<VERIFIED_PHARMATRACK_DATA>>>");
        assertThat(inputCaptor.getValue()).contains("USER QUERY:\nWhat is the status of manufacturing order MO-2026-0001?");
        assertThat(inputCaptor.getValue()).contains("Order Number: MO-2026-0001");
        assertThat(inputCaptor.getValue()).contains("Batch BAT-2026-0001-01");
        assertThat(inputCaptor.getValue()).doesNotContain("Internal manufacturing note");
    }

    @Test
    @DisplayName("Case-insensitive order extraction: mo-2026-0002 is normalized to MO-2026-0002")
    void query_lowercaseOrderNumber_isNormalizedCorrectly() {
        String orderNumber = "MO-2026-0002";
        OrderResponseDto mockOrder = createMockOrderDto(orderNumber);

        when(manufacturingOrderService.getOrderByOrderNumber(orderNumber)).thenReturn(mockOrder);
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse("Order summary for MO-2026-0002.", "gpt-6-luna", "resp_2", 80, 20, 100)
        );

        AiQueryRequestDto request = new AiQueryRequestDto("Summarize progress of mo-2026-0002.");
        AiQueryResponseDto response = service.query(request);

        assertThat(response.getContextIdentifier()).isEqualTo("MO-2026-0002");
        verify(manufacturingOrderService).getOrderByOrderNumber("MO-2026-0002");
    }

    @Test
    @DisplayName("Duplicate order number in query is deduplicated and treated as a single order")
    void query_sameOrderNumberRepeated_treatsAsSingleOrder() {
        String orderNumber = "MO-2026-0001";
        OrderResponseDto mockOrder = createMockOrderDto(orderNumber);

        when(manufacturingOrderService.getOrderByOrderNumber(orderNumber)).thenReturn(mockOrder);
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse("Order MO-2026-0001 is completed.", "gpt-6-luna", "resp_dup", 70, 15, 85)
        );

        AiQueryRequestDto request = new AiQueryRequestDto("What is MO-2026-0001 status? Also check MO-2026-0001 batches.");
        AiQueryResponseDto response = service.query(request);

        assertThat(response.getContextIdentifier()).isEqualTo("MO-2026-0001");
        verify(manufacturingOrderService).getOrderByOrderNumber("MO-2026-0001");
        verify(llmClient).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Ambiguous query containing two different order numbers returns dynamic clarification without invoking order service or LLM")
    void query_twoDifferentOrderNumbers_returnsClarificationWithoutServiceOrLlmCall() {
        AiQueryRequestDto request = new AiQueryRequestDto("Compare order MO-2026-0001 with order MO-2026-0002.");
        AiQueryResponseDto response = service.query(request);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).contains("Multiple manufacturing orders were detected: MO-2026-0001, MO-2026-0002");
        assertThat(response.getAnswer()).contains("Please query one manufacturing order at a time");
        assertThat(response.getContextIdentifier()).isNull();
        assertThat(response.getSources()).isEmpty();

        // Must NEVER call the database service or LLM
        verify(manufacturingOrderService, never()).getOrderByOrderNumber(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Ambiguous query containing three different order numbers returns clarification with all detected identifiers")
    void query_threeDifferentOrderNumbers_returnsClarificationWithoutServiceOrLlmCall() {
        AiQueryRequestDto request = new AiQueryRequestDto("Check MO-2026-0001, MO-2026-0002 and MO-2026-0003 for yield.");
        AiQueryResponseDto response = service.query(request);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).contains("Multiple manufacturing orders were detected: MO-2026-0001, MO-2026-0002, MO-2026-0003");
        assertThat(response.getSources()).isEmpty();

        verify(manufacturingOrderService, never()).getOrderByOrderNumber(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Context ID fallback: extracts order from contextId when not in query text")
    void query_orderNumberInContextId_isUsedWhenMissingFromQuery() {
        String orderNumber = "MO-2026-0005";
        OrderResponseDto mockOrder = createMockOrderDto(orderNumber);

        when(manufacturingOrderService.getOrderByOrderNumber(orderNumber)).thenReturn(mockOrder);
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse("Order MO-2026-0005 details.", "gpt-6-luna", "resp_5", 90, 20, 110)
        );

        AiQueryRequestDto request = new AiQueryRequestDto("What are the batches for this order?", "orders", "MANUFACTURING_ORDER", "MO-2026-0005");
        AiQueryResponseDto response = service.query(request);

        assertThat(response.getContextIdentifier()).isEqualTo("MO-2026-0005");
        verify(manufacturingOrderService).getOrderByOrderNumber("MO-2026-0005");
    }

    @Test
    @DisplayName("Unsupported query without order number returns clarification without invoking LLM")
    void query_unsupportedQueryNoOrderNumber_returnsClarificationWithoutLlmCall() {
        AiQueryRequestDto request = new AiQueryRequestDto("What is aspirin used for?");
        AiQueryResponseDto response = service.query(request);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).contains("Please specify a valid manufacturing order number");
        assertThat(response.getSources()).isEmpty();
        assertThat(response.getContextIdentifier()).isNull();

        verify(manufacturingOrderService, never()).getOrderByOrderNumber(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Invalid order identifier format returns clarification without invoking LLM")
    void query_invalidOrderIdentifier_returnsClarificationWithoutLlmCall() {
        AiQueryRequestDto request = new AiQueryRequestDto("Tell me about MO-99-1 and MO-XYZ");
        AiQueryResponseDto response = service.query(request);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).contains("Please specify a valid manufacturing order number");
        assertThat(response.getSources()).isEmpty();

        verify(manufacturingOrderService, never()).getOrderByOrderNumber(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Non-existent order returns safe not-found message without calling LLM or hallucinating")
    void query_nonExistentOrder_returnsNotFoundWithoutLlmCall() {
        String orderNumber = "MO-2026-9999";
        when(manufacturingOrderService.getOrderByOrderNumber(orderNumber))
                .thenThrow(new ResourceNotFoundException("Manufacturing order not found"));

        AiQueryRequestDto request = new AiQueryRequestDto("Tell me about MO-2026-9999");
        AiQueryResponseDto response = service.query(request);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).contains("Manufacturing order 'MO-2026-9999' was not found in PharmaTrack records");
        assertThat(response.getContextIdentifier()).isEqualTo("MO-2026-9999");
        assertThat(response.getSources()).isEmpty();

        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Throws CONFIGURATION_ERROR when AI Copilot is disabled without calling LLM")
    void query_whenAiDisabled_throwsConfigurationErrorWithoutLlmCall() {
        properties.setEnabled(false);

        AiQueryRequestDto request = new AiQueryRequestDto("What is MO-2026-0001 status?");

        assertThatThrownBy(() -> service.query(request))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.CONFIGURATION_ERROR);
                    assertThat(lex.getMessage()).contains("PharmaTrack AI Copilot is currently disabled");
                });

        verify(manufacturingOrderService, never()).getOrderByOrderNumber(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Unauthenticated actor throws AuthenticationCredentialsNotFoundException")
    void query_unauthenticatedActor_failsClosed() {
        when(authenticatedActorResolver.requireCurrentActor())
                .thenThrow(new AuthenticationCredentialsNotFoundException("Authenticated user context is required"));

        AiQueryRequestDto request = new AiQueryRequestDto("Status of MO-2026-0001?");

        assertThatThrownBy(() -> service.query(request))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessageContaining("Authenticated user context is required");

        verify(manufacturingOrderService, never()).getOrderByOrderNumber(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Propagates provider failures cleanly without leaking secrets")
    void query_providerFailure_propagatesCleanly() {
        String orderNumber = "MO-2026-0001";
        OrderResponseDto mockOrder = createMockOrderDto(orderNumber);
        when(manufacturingOrderService.getOrderByOrderNumber(orderNumber)).thenReturn(mockOrder);

        when(llmClient.generate(anyString(), anyString()))
                .thenThrow(new LlmClientException(LlmClientException.ErrorCategory.RATE_LIMITED, 429, "Rate limit reached"));

        AiQueryRequestDto request = new AiQueryRequestDto("Status of MO-2026-0001?");

        assertThatThrownBy(() -> service.query(request))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.RATE_LIMITED);
                    assertThat(lex.getHttpStatus()).isEqualTo(429);
                });
    }

    @Test
    @DisplayName("Rejects null or blank query with IllegalArgumentException")
    void query_blankQuery_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.query(new AiQueryRequestDto("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query must not be blank");

        assertThatThrownBy(() -> service.query(new AiQueryRequestDto(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query must not be blank");
    }

    @Test
    @DisplayName("extractOrderNumbers helper correctly categorizes empty, single, and ambiguous queries")
    void extractOrderNumbers_testVariousScenarios() {
        // No match
        AiCopilotService.OrderExtractionResult emptyResult = service.extractOrderNumbers("No orders here", null);
        assertThat(emptyResult.isEmpty()).isTrue();
        assertThat(emptyResult.isSingle()).isFalse();
        assertThat(emptyResult.isAmbiguous()).isFalse();

        // 1 match
        AiCopilotService.OrderExtractionResult singleResult = service.extractOrderNumbers("Order MO-2026-0001 details", null);
        assertThat(singleResult.isSingle()).isTrue();
        assertThat(singleResult.getSingleOrderNumber()).isEqualTo("MO-2026-0001");

        // Duplicate match
        AiCopilotService.OrderExtractionResult dupResult = service.extractOrderNumbers("MO-2026-0001 and mo-2026-0001", null);
        assertThat(dupResult.isSingle()).isTrue();
        assertThat(dupResult.getSingleOrderNumber()).isEqualTo("MO-2026-0001");

        // 2 distinct matches
        AiCopilotService.OrderExtractionResult multiResult = service.extractOrderNumbers("MO-2026-0001 vs MO-2026-0002", null);
        assertThat(multiResult.isAmbiguous()).isTrue();
        assertThat(multiResult.orderNumbers()).containsExactly("MO-2026-0001", "MO-2026-0002");
    }

    @Test
    @DisplayName("Regex hardening: strictly enforces boundaries, casing, sequence length constraints, and ambiguity across query and contextId")
    void extractOrderNumbers_regexHardening_validatesAllConstraints() {
        // 1. MO-2026-0001 is accepted
        AiCopilotService.OrderExtractionResult r1 = service.extractOrderNumbers("Order MO-2026-0001 details", null);
        assertThat(r1.isSingle()).isTrue();
        assertThat(r1.getSingleOrderNumber()).isEqualTo("MO-2026-0001");

        // 2. mo-2026-0001 is accepted and normalized
        AiCopilotService.OrderExtractionResult r2 = service.extractOrderNumbers("Check mo-2026-0001 please", null);
        assertThat(r2.isSingle()).isTrue();
        assertThat(r2.getSingleOrderNumber()).isEqualTo("MO-2026-0001");

        // 3. MO-2026-10000 is accepted (5-digit sequence for IDs >= 10000)
        AiCopilotService.OrderExtractionResult r3 = service.extractOrderNumbers("Order MO-2026-10000 status", null);
        assertThat(r3.isSingle()).isTrue();
        assertThat(r3.getSingleOrderNumber()).isEqualTo("MO-2026-10000");

        // 4. MO-2026-1 is rejected (less than 4 digits)
        AiCopilotService.OrderExtractionResult r4 = service.extractOrderNumbers("Order MO-2026-1 status", null);
        assertThat(r4.isEmpty()).isTrue();

        // 5. PROD-MO-2026-0001 is rejected (compound token with hyphen prefix)
        AiCopilotService.OrderExtractionResult r5 = service.extractOrderNumbers("Item PROD-MO-2026-0001 status", null);
        assertThat(r5.isEmpty()).isTrue();

        // 6. MO-2026-0001-01 is rejected (compound token with hyphen suffix)
        AiCopilotService.OrderExtractionResult r6 = service.extractOrderNumbers("Batch MO-2026-0001-01 status", null);
        assertThat(r6.isEmpty()).isTrue();

        // 7. A 20-digit sequence is rejected
        String twentyDigits = "MO-2026-12345678901234567890";
        AiCopilotService.OrderExtractionResult r7 = service.extractOrderNumbers("Order " + twentyDigits, null);
        assertThat(r7.isEmpty()).isTrue();

        // Verify that a 19-digit sequence (max Java Long) is accepted
        String nineteenDigits = "MO-2026-1234567890123456789";
        AiCopilotService.OrderExtractionResult r7b = service.extractOrderNumbers("Order " + nineteenDigits, null);
        assertThat(r7b.isSingle()).isTrue();
        assertThat(r7b.getSingleOrderNumber()).isEqualTo(nineteenDigits);

        // 8. Two valid identifiers separated by punctuation are still detected as ambiguous
        AiCopilotService.OrderExtractionResult r8 = service.extractOrderNumbers("Compare [MO-2026-0001];(MO-2026-0002)!", null);
        assertThat(r8.isAmbiguous()).isTrue();
        assertThat(r8.orderNumbers()).containsExactly("MO-2026-0001", "MO-2026-0002");

        // 9. Multiple identifiers in contextId are detected as ambiguous when query contains no identifier
        AiCopilotService.OrderExtractionResult r9 = service.extractOrderNumbers("What is the status?", "MO-2026-0001, MO-2026-0002");
        assertThat(r9.isAmbiguous()).isTrue();
        assertThat(r9.orderNumbers()).containsExactly("MO-2026-0001", "MO-2026-0002");
    }

    @Test
    @DisplayName("Ambiguous multiple identifiers in contextId triggers clarification without LLM invocation")
    void query_multipleIdentifiersInContextId_returnsClarificationWithoutLlmCall() {
        AiQueryRequestDto request = new AiQueryRequestDto(
                "Compare these orders",
                "orders",
                "MANUFACTURING_ORDER",
                "MO-2026-0001, MO-2026-0002"
        );
        AiQueryResponseDto response = service.query(request);

        assertThat(response).isNotNull();
        assertThat(response.getAnswer()).contains("Multiple manufacturing orders were detected: MO-2026-0001, MO-2026-0002");
        assertThat(response.getAnswer()).contains("Please query one manufacturing order at a time");
        assertThat(response.getSources()).isEmpty();

        verify(manufacturingOrderService, never()).getOrderByOrderNumber(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Follow-up conversational query reuses active context and delivers verified data to LLM")
    void query_followUpQueryWithoutOrderNumber_reusesActiveContextId() {
        String orderNumber = "MO-2026-0001";
        OrderResponseDto mockOrder = createMockOrderDto(orderNumber);

        when(manufacturingOrderService.getOrderByOrderNumber(orderNumber)).thenReturn(mockOrder);
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse("Planned quantity is 50,000 TABLETS and actual yield is 49,800 TABLETS (99.6%).",
                        "gpt-6-luna", "resp_followup", 130, 40, 170)
        );

        AiQueryRequestDto followUpRequest = new AiQueryRequestDto(
                "Compare the planned quantity with the actual yield of both batches.",
                "orders",
                "MANUFACTURING_ORDER",
                "MO-2026-0001"
        );

        AiQueryResponseDto response = service.query(followUpRequest);

        assertThat(response).isNotNull();
        assertThat(response.getContextIdentifier()).isEqualTo("MO-2026-0001");
        assertThat(response.getAnswer()).contains("99.6%");
        assertThat(response.getSources()).isNotEmpty();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> instructionsCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(instructionsCaptor.capture(), promptCaptor.capture());

        assertThat(instructionsCaptor.getValue()).contains("When answering follow-up questions, comparing planned quantities with actual yields");
        assertThat(promptCaptor.getValue()).contains("USER QUERY:\nCompare the planned quantity with the actual yield of both batches.");
        assertThat(promptCaptor.getValue()).contains("Order Number: MO-2026-0001");
        verify(manufacturingOrderService).getOrderByOrderNumber("MO-2026-0001");
    }

    @Test
    @DisplayName("Explicit new order in query overrides active contextId and switches context")
    void query_explicitNewOrderInQuery_overridesActiveContextId() {
        String previousOrder = "MO-2026-0001";
        String newOrder = "MO-2026-0002";
        OrderResponseDto mockOrder2 = createMockOrderDto(newOrder);

        when(manufacturingOrderService.getOrderByOrderNumber(newOrder)).thenReturn(mockOrder2);
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse("Switched to MO-2026-0002.", "gpt-6-luna", "resp_switch", 90, 20, 110)
        );

        AiQueryRequestDto request = new AiQueryRequestDto(
                "What about manufacturing order MO-2026-0002?",
                "orders",
                "MANUFACTURING_ORDER",
                previousOrder
        );

        AiQueryResponseDto response = service.query(request);

        assertThat(response.getContextIdentifier()).isEqualTo(newOrder);
        verify(manufacturingOrderService).getOrderByOrderNumber(newOrder);
        verify(manufacturingOrderService, never()).getOrderByOrderNumber(previousOrder);
    }

    @Test
    @DisplayName("Non-manufacturing contextType ignores contextId fallback and requests order number")
    void query_nonManufacturingContextType_ignoresContextIdFallback() {
        AiQueryRequestDto request = new AiQueryRequestDto(
                "What is the status of this item?",
                "inventory",
                "INVENTORY_ITEM",
                "MO-2026-0001"
        );

        AiQueryResponseDto response = service.query(request);

        assertThat(response.getAnswer()).contains("Please specify a valid manufacturing order number");
        assertThat(response.getContextIdentifier()).isNull();
        verify(manufacturingOrderService, never()).getOrderByOrderNumber(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Consecutive queries always retrieve fresh records from ManufacturingOrderService")
    void query_consecutiveQueries_alwaysRetrievesFreshRecords() {
        String orderNumber = "MO-2026-0001";
        OrderResponseDto mockOrder1 = createMockOrderDto(orderNumber);
        OrderResponseDto mockOrder2 = createMockOrderDto(orderNumber);

        when(manufacturingOrderService.getOrderByOrderNumber(orderNumber))
                .thenReturn(mockOrder1)
                .thenReturn(mockOrder2);
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse("Answer 1", "gpt-6-luna", "r1", 50, 10, 60),
                new LlmResponse("Answer 2", "gpt-6-luna", "r2", 50, 10, 60)
        );

        AiQueryRequestDto req1 = new AiQueryRequestDto("What is MO-2026-0001 status?");
        AiQueryRequestDto req2 = new AiQueryRequestDto("What are its batches?", "orders", "MANUFACTURING_ORDER", orderNumber);

        service.query(req1);
        service.query(req2);

        // Verify fresh database retrieval on each query
        verify(manufacturingOrderService, org.mockito.Mockito.times(2)).getOrderByOrderNumber(orderNumber);
    }

    // =========================================================================
    // Batch Intelligence Unit Tests
    // =========================================================================

    @Test
    @DisplayName("Batch query by Production Supervisor retrieves dossier, grounds facts, and invokes LLM")
    void query_batchContext_supervisor_generatesReport() {
        String batchNum = "BAT-2026-0001-01";
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber(batchNum);
        dossier.setBatchStatus(BatchStatus.COMPLETED);
        dossier.setBatchSize(new BigDecimal("100.0000"));
        dossier.setActualYield(new BigDecimal("98.5000"));

        when(batchTraceabilityService.getTraceabilityDossierByIdentifier(batchNum)).thenReturn(dossier);
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse("### Batch Report", "gpt-6-luna", "r1", 50, 20, 70)
        );

        AiQueryRequestDto request = new AiQueryRequestDto(
                "Analyze batch " + batchNum, "Batch Traceability", "BATCH", batchNum
        );

        AiQueryResponseDto response = service.query(request);

        assertThat(response.getContextType()).isEqualTo("BATCH");
        assertThat(response.getContextIdentifier()).isEqualTo(batchNum);
        assertThat(response.getAnswer()).isEqualTo("### Batch Report");
        verify(batchTraceabilityService).getTraceabilityDossierByIdentifier(batchNum);
        verify(llmClient).generate(eq(AiCopilotService.BATCH_SYSTEM_INSTRUCTIONS), org.mockito.ArgumentMatchers.contains("BAT-2026-0001-01"));
    }

    @Test
    @DisplayName("Batch query by non-supervisor throws AccessDeniedException")
    void query_batchContext_nonSupervisor_throwsAccessDenied() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR")).thenReturn(false);

        AiQueryRequestDto request = new AiQueryRequestDto(
                "Analyze batch BAT-2026-0001-01", "Batch Traceability", "BATCH", "BAT-2026-0001-01"
        );

        assertThatThrownBy(() -> service.query(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("restricted to Production Supervisors");

        verify(batchTraceabilityService, never()).getTraceabilityDossierByIdentifier(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Ambiguous multi-batch query returns clarification without invoking LLM or database")
    void query_batchContext_ambiguousBatches_returnsClarificationWithoutLlm() {
        AiQueryRequestDto request = new AiQueryRequestDto(
                "Compare BAT-2026-0001-01 and BAT-2026-0001-02", "Batch Traceability", "BATCH", null
        );

        AiQueryResponseDto response = service.query(request);

        assertThat(response.getAnswer()).contains("Multiple batches were detected");
        assertThat(response.getContextType()).isEqualTo("BATCH");
        verify(batchTraceabilityService, never()).getTraceabilityDossierByIdentifier(anyString());
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Deterministic batch extraction detects standard numbers, QR codes, and handles edge cases")
    void extractBatchIdentifiers_extractsExpectedFormats() {
        AiCopilotService.BatchExtractionResult result = service.extractBatchIdentifiers(
                "Please analyze BAT-2026-0001-01 and QR-PT-BAT-2026-0002-02", null
        );
        assertThat(result.batchIdentifiers()).containsExactlyInAnyOrder("BAT-2026-0001-01", "QR-PT-BAT-2026-0002-02");
        assertThat(result.isAmbiguous()).isTrue();

        AiCopilotService.BatchExtractionResult singleResult = service.extractBatchIdentifiers(
                "Status of BAT-2026-0005-01 please", null
        );
        assertThat(singleResult.isSingle()).isTrue();
        assertThat(singleResult.getSingleBatchIdentifier()).isEqualTo("BAT-2026-0005-01");

        AiCopilotService.BatchExtractionResult contextResult = service.extractBatchIdentifiers(
                "What is the yield?", "BAT-2026-0010-01"
        );
        assertThat(contextResult.isSingle()).isTrue();
        assertThat(contextResult.getSingleBatchIdentifier()).isEqualTo("BAT-2026-0010-01");
    }
}
