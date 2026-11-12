package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.controller.BatchController;
import com.pharmatrack.controller.ProductionExecutionController;
import com.pharmatrack.dto.BatchExecutionQueueItemDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchExecutionWorkspaceDto;
import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.InvalidExecutionStepException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.security.SecurityConfig;
import com.pharmatrack.service.ManufacturingOrderService;
import com.pharmatrack.service.ProductionExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ProductionExecutionController.class, BatchController.class})
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@WithMockUser(username = "operator.a", roles = "OPERATOR")
class ProductionExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockBean
    private ProductionExecutionService executionService;

    @MockBean
    private ManufacturingOrderService orderService;

    private BatchExecutionQueueItemDto queueItemDto;
    private BatchExecutionStepResponseDto pendingStepDto;
    private BatchExecutionStepResponseDto inProgressStepDto;
    private BatchExecutionStepResponseDto completedStepDto;
    private BatchExecutionWorkspaceDto workspaceDto;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.of(2026, 3, 15, 9, 30, 0);

        queueItemDto = new BatchExecutionQueueItemDto(
                101L,
                "BAT-2026-0001-01",
                BatchStatus.IN_PROGRESS,
                "MO-2026-0001",
                "Amoxicillin Capsules",
                "RCP-AMOX-250-V3",
                3,
                new BigDecimal("250.0000"),
                "kg",
                now
        );

        pendingStepDto = new BatchExecutionStepResponseDto(
                1001L,
                1,
                "Granulation Mixing",
                "Blend active ingredients for 15 minutes at 120 RPM.",
                ExecutionStepStatus.PENDING,
                null,
                null,
                null,
                null,
                null
        );

        inProgressStepDto = new BatchExecutionStepResponseDto(
                1001L,
                1,
                "Granulation Mixing",
                "Blend active ingredients for 15 minutes at 120 RPM.",
                ExecutionStepStatus.IN_PROGRESS,
                null,
                null,
                null,
                now.plusMinutes(10),
                null
        );

        completedStepDto = new BatchExecutionStepResponseDto(
                1001L,
                1,
                "Granulation Mixing",
                "Blend active ingredients for 15 minutes at 120 RPM.",
                ExecutionStepStatus.COMPLETED,
                "120 RPM / 15 min",
                "Homogeneous blend verified visually.",
                "Operator A",
                now.plusMinutes(10),
                now.plusMinutes(25)
        );

        BatchExecutionStepResponseDto secondStepDto = new BatchExecutionStepResponseDto(
                1002L,
                2,
                "Compression",
                "Compress tablets at 18 kN force.",
                ExecutionStepStatus.PENDING,
                null,
                null,
                null,
                null,
                null
        );

        workspaceDto = new BatchExecutionWorkspaceDto(
                101L,
                "BAT-2026-0001-01",
                BatchStatus.IN_PROGRESS,
                "MO-2026-0001",
                "Amoxicillin Capsules",
                "RCP-AMOX-250-V3",
                3,
                new BigDecimal("250.0000"),
                "kg",
                List.of(pendingStepDto, secondStepDto)
        );
    }

    // -------------------------------------------------------------------------
    // 1. Route Coexistence & Exposed Endpoint Scope
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ProductionExecutionController should expose the expected execution endpoints and no notes endpoint")
    void controllerEndpoints_MatchExactPhase5Contract() {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();

        Set<String> executionRoutes = handlerMethods.entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().equals(ProductionExecutionController.class))
                .map(entry -> entry.getKey().getMethodsCondition().getMethods().stream()
                        .map(RequestMethod::name)
                        .collect(Collectors.joining(","))
                        + " "
                        + entry.getKey().getPatternValues())
                .collect(Collectors.toSet());

        assertThat(executionRoutes).containsExactlyInAnyOrder(
                "GET [/api/batches/execution]",
                "GET [/api/batches/{batchId}/steps]",
                "POST [/api/batches/{batchId}/steps/initialize]",
                "PATCH [/api/batches/{batchId}/steps/{stepId}/start]",
                "PATCH [/api/batches/{batchId}/steps/{stepId}/complete]"
        );

        Set<String> batchGetRoutes = handlerMethods.entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().equals(BatchController.class))
                .map(entry -> entry.getKey().getMethodsCondition().getMethods().stream()
                        .map(RequestMethod::name)
                        .collect(Collectors.joining(","))
                        + " "
                        + entry.getKey().getPatternValues())
                .collect(Collectors.toSet());

        assertThat(batchGetRoutes).contains("GET [/api/batches/{id:\\d+}]");
    }

    @Test
    @DisplayName("GET /api/batches/execution should route to getExecutionQueue() while GET /api/batches/5 routes to BatchController")
    void routeCoexistence_StaticExecutionVsNumericBatchId5_ResolvesCleanly() throws Exception {
        BatchResponseDto batchFiveDto = new BatchResponseDto(
                5L, "BAT-2026-0001-05", 1L, "MO-2026-0001", new BigDecimal("250.0000"), "kg",
                null, BatchStatus.IN_PROGRESS, LocalDate.of(2026, 3, 1), null,
                LocalDateTime.now(), null, LocalDateTime.now(), LocalDateTime.now()
        );

        when(executionService.getExecutionQueue()).thenReturn(List.of(queueItemDto));
        when(orderService.getBatchById(5L)).thenReturn(batchFiveDto);

        mockMvc.perform(get("/api/batches/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchId").value(101L))
                .andExpect(jsonPath("$[0].batchNumber").value("BAT-2026-0001-01"));

        mockMvc.perform(get("/api/batches/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5L))
                .andExpect(jsonPath("$.batchNumber").value("BAT-2026-0001-05"));

        verify(executionService).getExecutionQueue();
        verifyNoMoreInteractions(executionService);
        verify(orderService).getBatchById(5L);
        verifyNoMoreInteractions(orderService);
    }

    @Test
    @DisplayName("GET /api/batches/999 (unknown numeric ID) should retain 404 Not Found behavior alongside execution routes")
    void routeCoexistence_UnknownNumericBatchId_Returns404() throws Exception {
        when(orderService.getBatchById(999L))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 999"));

        mockMvc.perform(get("/api/batches/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Batch not found with id: 999"));

        verify(orderService).getBatchById(999L);
        verifyNoInteractions(executionService);
    }

    // -------------------------------------------------------------------------
    // 2. GET /api/batches/execution - Production Execution Queue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/batches/execution - Should return populated queue and 200 OK")
    void getExecutionQueue_PopulatedList_Returns200() throws Exception {
        when(executionService.getExecutionQueue()).thenReturn(List.of(queueItemDto));

        mockMvc.perform(get("/api/batches/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].batchId").value(101L))
                .andExpect(jsonPath("$[0].batchNumber").value("BAT-2026-0001-01"))
                .andExpect(jsonPath("$[0].batchStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[0].orderNumber").value("MO-2026-0001"))
                .andExpect(jsonPath("$[0].productName").value("Amoxicillin Capsules"))
                .andExpect(jsonPath("$[0].recipeCode").value("RCP-AMOX-250-V3"))
                .andExpect(jsonPath("$[0].recipeVersion").value(3))
                .andExpect(jsonPath("$[0].batchSize").value(250.0000))
                .andExpect(jsonPath("$[0].batchUnit").value("kg"))
                .andExpect(jsonPath("$[0].startTime").exists());

        verify(executionService).getExecutionQueue();
    }

    @Test
    @DisplayName("GET /api/batches/execution - Should return empty array and 200 OK when no active batches exist")
    void getExecutionQueue_EmptyList_Returns200() throws Exception {
        when(executionService.getExecutionQueue()).thenReturn(List.of());

        mockMvc.perform(get("/api/batches/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        verify(executionService).getExecutionQueue();
    }

    // -------------------------------------------------------------------------
    // 3. GET /api/batches/{batchId}/steps - Batch Execution Workspace
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/batches/{batchId}/steps - Should return workspace with ordered steps and 200 OK")
    void getBatchExecutionWorkspace_Success_Returns200() throws Exception {
        when(executionService.getBatchExecutionWorkspace(101L)).thenReturn(workspaceDto);

        mockMvc.perform(get("/api/batches/101/steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(101L))
                .andExpect(jsonPath("$.batchNumber").value("BAT-2026-0001-01"))
                .andExpect(jsonPath("$.batchStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.orderNumber").value("MO-2026-0001"))
                .andExpect(jsonPath("$.productName").value("Amoxicillin Capsules"))
                .andExpect(jsonPath("$.recipeCode").value("RCP-AMOX-250-V3"))
                .andExpect(jsonPath("$.recipeVersion").value(3))
                .andExpect(jsonPath("$.batchSize").value(250.0000))
                .andExpect(jsonPath("$.batchUnit").value("kg"))
                .andExpect(jsonPath("$.executionSteps.length()").value(2))
                .andExpect(jsonPath("$.executionSteps[0].id").value(1001L))
                .andExpect(jsonPath("$.executionSteps[0].stepNumber").value(1))
                .andExpect(jsonPath("$.executionSteps[0].operationName").value("Granulation Mixing"))
                .andExpect(jsonPath("$.executionSteps[0].instructions").value("Blend active ingredients for 15 minutes at 120 RPM."))
                .andExpect(jsonPath("$.executionSteps[0].status").value("PENDING"))
                .andExpect(jsonPath("$.executionSteps[1].id").value(1002L))
                .andExpect(jsonPath("$.executionSteps[1].stepNumber").value(2));

        verify(executionService).getBatchExecutionWorkspace(101L);
    }

    @Test
    @DisplayName("GET /api/batches/{batchId}/steps - Should return 404 Not Found when batch does not exist")
    void getBatchExecutionWorkspace_BatchNotFound_Returns404() throws Exception {
        when(executionService.getBatchExecutionWorkspace(999L))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 999"));

        mockMvc.perform(get("/api/batches/999/steps"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Batch not found with id: 999"));

        verify(executionService).getBatchExecutionWorkspace(999L);
    }

    @Test
    @DisplayName("GET /api/batches/{batchId}/steps - Should return 400 Bad Request for non-numeric batchId")
    void getBatchExecutionWorkspace_InvalidBatchIdType_Returns400() throws Exception {
        mockMvc.perform(get("/api/batches/not-a-number/steps"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        verify(executionService, never()).getBatchExecutionWorkspace(any());
        verify(executionService, never()).initializeExecutionSteps(any());
    }

    // -------------------------------------------------------------------------
    // 3B. POST /api/batches/{batchId}/steps/initialize - Backfill Execution Steps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/batches/{batchId}/steps/initialize - Should initialize steps and return 200 OK")
    void initializeExecutionSteps_Success_Returns200() throws Exception {
        BatchExecutionWorkspaceDto initializedWorkspace = new BatchExecutionWorkspaceDto(
                5L,
                "BAT-2026-0004-01",
                BatchStatus.IN_PROGRESS,
                "MO-2026-0004",
                "Amoxicillin Capsules",
                "RCP-AMOX-250-V2",
                2,
                new BigDecimal("250.0000"),
                "kg",
                List.of(pendingStepDto, new BatchExecutionStepResponseDto(
                        1002L, 2, "Compression", "Compress tablets at 18 kN force.",
                        ExecutionStepStatus.PENDING, null, null, null, null, null
                ))
        );
        when(executionService.initializeExecutionSteps(5L)).thenReturn(initializedWorkspace);

        mockMvc.perform(post("/api/batches/5/steps/initialize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(5L))
                .andExpect(jsonPath("$.batchNumber").value("BAT-2026-0004-01"))
                .andExpect(jsonPath("$.batchStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.executionSteps.length()").value(2))
                .andExpect(jsonPath("$.executionSteps[0].stepNumber").value(1))
                .andExpect(jsonPath("$.executionSteps[0].status").value("PENDING"))
                .andExpect(jsonPath("$.executionSteps[1].stepNumber").value(2))
                .andExpect(jsonPath("$.executionSteps[1].status").value("PENDING"));

        verify(executionService).initializeExecutionSteps(5L);
        verify(executionService, never()).getBatchExecutionWorkspace(any());
    }

    @Test
    @DisplayName("POST /api/batches/{batchId}/steps/initialize - Should return 404 Not Found when batch does not exist")
    void initializeExecutionSteps_BatchNotFound_Returns404() throws Exception {
        when(executionService.initializeExecutionSteps(999L))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 999"));

        mockMvc.perform(post("/api/batches/999/steps/initialize"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Batch not found with id: 999"));

        verify(executionService).initializeExecutionSteps(999L);
    }

    @Test
    @DisplayName("POST /api/batches/{batchId}/steps/initialize - Should return 400 Bad Request on InvalidExecutionStepException")
    void initializeExecutionSteps_InvalidStateOrInconsistent_Returns400() throws Exception {
        when(executionService.initializeExecutionSteps(5L))
                .thenThrow(new InvalidExecutionStepException(
                        "Cannot initialize execution steps for batch BAT-2026-0004-01: existing execution steps count (1) does not match order recipe 'RCP-AMOX-250-V2' version 2 step count (2)"
                ));

        mockMvc.perform(post("/api/batches/5/steps/initialize"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("does not match order recipe")));

        verify(executionService).initializeExecutionSteps(5L);
    }

    @Test
    @DisplayName("POST /api/batches/{batchId}/steps/initialize - Should return 400 Bad Request for non-numeric batchId")
    void initializeExecutionSteps_InvalidBatchIdType_Returns400() throws Exception {
        mockMvc.perform(post("/api/batches/not-a-number/steps/initialize"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        verify(executionService, never()).initializeExecutionSteps(any());
    }

    // -------------------------------------------------------------------------
    // 4. PATCH /api/batches/{batchId}/steps/{stepId}/start - Start Step
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/start - Should start step without body and return 200 OK")
    void startExecutionStep_Success_Returns200() throws Exception {
        when(executionService.startExecutionStep(101L, 1001L)).thenReturn(inProgressStepDto);

        mockMvc.perform(patch("/api/batches/101/steps/1001/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1001L))
                .andExpect(jsonPath("$.stepNumber").value(1))
                .andExpect(jsonPath("$.operationName").value("Granulation Mixing"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.completedAt").value(nullValue()));

        verify(executionService).startExecutionStep(101L, 1001L);
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/start - Should return 400 Bad Request on InvalidExecutionStepException")
    void startExecutionStep_InvalidTransition_Returns400() throws Exception {
        when(executionService.startExecutionStep(101L, 1002L))
                .thenThrow(new InvalidExecutionStepException(
                        "Cannot start step 2 (Compression) before earlier step 1 (Granulation Mixing) is COMPLETED"
                ));

        mockMvc.perform(patch("/api/batches/101/steps/1002/start"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("before earlier step 1")));

        verify(executionService).startExecutionStep(101L, 1002L);
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/start - Should return 404 Not Found when batch or step is missing")
    void startExecutionStep_NotFound_Returns404() throws Exception {
        when(executionService.startExecutionStep(101L, 9999L))
                .thenThrow(new ResourceNotFoundException("Execution step not found with id: 9999"));

        mockMvc.perform(patch("/api/batches/101/steps/9999/start"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Execution step not found with id: 9999"));

        verify(executionService).startExecutionStep(101L, 9999L);
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/start - Should return 400 Bad Request for non-numeric stepId")
    void startExecutionStep_InvalidStepIdType_Returns400() throws Exception {
        mockMvc.perform(patch("/api/batches/101/steps/invalid-id/start"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        verify(executionService, never()).startExecutionStep(any(), any());
    }

    // -------------------------------------------------------------------------
    // 5. PATCH /api/batches/{batchId}/steps/{stepId}/complete - Complete Step
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/complete - Should complete step and ignore forged executedBy JSON field")
    void completeExecutionStep_Success_Returns200() throws Exception {
        when(executionService.completeExecutionStep(eq(101L), eq(1001L), any(CompleteExecutionStepRequestDto.class)))
                .thenReturn(completedStepDto);

        mockMvc.perform(patch("/api/batches/101/steps/1001/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualValue\":\"120 RPM / 15 min\",\"observations\":\"Homogeneous blend verified visually.\",\"executedBy\":\"Forged Operator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1001L))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.actualValue").value("120 RPM / 15 min"))
                .andExpect(jsonPath("$.observations").value("Homogeneous blend verified visually."))
                .andExpect(jsonPath("$.executedBy").value("Operator A"))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.completedAt").exists());

        ArgumentCaptor<CompleteExecutionStepRequestDto> captor = ArgumentCaptor.forClass(CompleteExecutionStepRequestDto.class);
        verify(executionService).completeExecutionStep(eq(101L), eq(1001L), captor.capture());
        assertThat(captor.getValue().getActualValue()).isEqualTo("120 RPM / 15 min");
        assertThat(captor.getValue().getObservations()).isEqualTo("Homogeneous blend verified visually.");
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/complete - Should succeed with only mandatory actualValue")
    void completeExecutionStep_ActualValueOnly_Returns200() throws Exception {
        BatchExecutionStepResponseDto minimalCompletedDto = new BatchExecutionStepResponseDto(
                1001L,
                1,
                "Granulation Mixing",
                "Blend active ingredients for 15 minutes at 120 RPM.",
                ExecutionStepStatus.COMPLETED,
                "42.5 C",
                null,
                null,
                LocalDateTime.of(2026, 3, 15, 9, 40, 0),
                LocalDateTime.of(2026, 3, 15, 9, 55, 0)
        );
        when(executionService.completeExecutionStep(eq(101L), eq(1001L), any(CompleteExecutionStepRequestDto.class)))
                .thenReturn(minimalCompletedDto);

        mockMvc.perform(patch("/api/batches/101/steps/1001/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualValue\":\"42.5 C\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.actualValue").value("42.5 C"))
                .andExpect(jsonPath("$.observations").value(nullValue()))
                .andExpect(jsonPath("$.executedBy").value(nullValue()));

        verify(executionService).completeExecutionStep(eq(101L), eq(1001L), any(CompleteExecutionStepRequestDto.class));
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/complete - Should return 400 when actualValue is missing")
    void completeExecutionStep_MissingActualValue_Returns400() throws Exception {
        mockMvc.perform(patch("/api/batches/101/steps/1001/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.actualValue").exists());

        verify(executionService, never()).completeExecutionStep(any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/complete - Should return 400 when actualValue is blank")
    void completeExecutionStep_BlankActualValue_Returns400() throws Exception {
        CompleteExecutionStepRequestDto blankRequest = new CompleteExecutionStepRequestDto("   ", "Obs");

        mockMvc.perform(patch("/api/batches/101/steps/1001/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.actualValue").exists());

        verify(executionService, never()).completeExecutionStep(any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/complete - Should return 400 when actualValue exceeds 255 characters")
    void completeExecutionStep_OversizedActualValue_Returns400() throws Exception {
        CompleteExecutionStepRequestDto oversizedRequest = new CompleteExecutionStepRequestDto(
                "A".repeat(256),
                "Obs"
        );

        mockMvc.perform(patch("/api/batches/101/steps/1001/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oversizedRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.actualValue").exists());

        verify(executionService, never()).completeExecutionStep(any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/complete - Should return 400 when observations exceed bounds")
    void completeExecutionStep_OversizedOptionalFields_Returns400() throws Exception {
        CompleteExecutionStepRequestDto oversizedOptionalRequest = new CompleteExecutionStepRequestDto(
                "120 RPM",
                "N".repeat(2001)
        );

        mockMvc.perform(patch("/api/batches/101/steps/1001/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oversizedOptionalRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.observations").exists());

        verify(executionService, never()).completeExecutionStep(any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/complete - Should return 400 when request body is missing")
    void completeExecutionStep_MissingRequestBody_Returns400() throws Exception {
        mockMvc.perform(patch("/api/batches/101/steps/1001/complete"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        verify(executionService, never()).completeExecutionStep(any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/complete - Should return 400 on InvalidExecutionStepException")
    void completeExecutionStep_InvalidState_Returns400() throws Exception {
        CompleteExecutionStepRequestDto requestDto = new CompleteExecutionStepRequestDto("120 RPM", null);
        when(executionService.completeExecutionStep(eq(101L), eq(1001L), any(CompleteExecutionStepRequestDto.class)))
                .thenThrow(new InvalidExecutionStepException(
                        "Cannot complete execution step 1 (Granulation Mixing) from status PENDING. Step must be IN_PROGRESS before completion."
                ));

        mockMvc.perform(patch("/api/batches/101/steps/1001/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Step must be IN_PROGRESS")));

        verify(executionService).completeExecutionStep(eq(101L), eq(1001L), any(CompleteExecutionStepRequestDto.class));
    }

    @Test
    @DisplayName("PATCH /api/batches/{batchId}/steps/{stepId}/complete - Should return 404 Not Found when batch or step is missing")
    void completeExecutionStep_NotFound_Returns404() throws Exception {
        CompleteExecutionStepRequestDto requestDto = new CompleteExecutionStepRequestDto("120 RPM", null);
        when(executionService.completeExecutionStep(eq(999L), eq(1001L), any(CompleteExecutionStepRequestDto.class)))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 999"));

        mockMvc.perform(patch("/api/batches/999/steps/1001/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Batch not found with id: 999"));

        verify(executionService).completeExecutionStep(eq(999L), eq(1001L), any(CompleteExecutionStepRequestDto.class));
    }

    @Test
    @WithMockUser(username = "supervisor.user", roles = "PRODUCTION_SUPERVISOR")
    @DisplayName("Non-OPERATOR roles receive 403 Forbidden on step execution mutations")
    void nonOperatorDeniedStepMutations() throws Exception {
        mockMvc.perform(post("/api/batches/5/steps/initialize")).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/batches/101/steps/1001/start")).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/batches/101/steps/1001/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CompleteExecutionStepRequestDto("120 RPM", null))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(executionService);
    }
}

