package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.controller.BatchController;
import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.InvalidOrderException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.security.SecurityConfig;
import com.pharmatrack.service.ManufacturingOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BatchController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@WithMockUser(username = "supervisor.user", roles = "PRODUCTION_SUPERVISOR")
class BatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ManufacturingOrderService orderService;

    private BatchResponseDto sampleBatchDto;

    @BeforeEach
    void setUp() {
        sampleBatchDto = new BatchResponseDto(
                101L,
                "BAT-2026-0001-01",
                1L,
                "MO-2026-0001",
                new BigDecimal("250.0000"),
                "kg",
                null,
                BatchStatus.PLANNED,
                LocalDate.of(2026, 3, 1),
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    // -------------------------------------------------------------------------
    // 1. GET /api/batches/{id} - Retrieve Batch by ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/batches/{id} - Should return batch and 200 OK when found")
    void getBatchById_Success_Returns200() throws Exception {
        when(orderService.getBatchById(101L)).thenReturn(sampleBatchDto);

        mockMvc.perform(get("/api/batches/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101L))
                .andExpect(jsonPath("$.batchNumber").value("BAT-2026-0001-01"))
                .andExpect(jsonPath("$.orderId").value(1L))
                .andExpect(jsonPath("$.orderNumber").value("MO-2026-0001"))
                .andExpect(jsonPath("$.batchSize").value(250.0000))
                .andExpect(jsonPath("$.batchUnit").value("kg"))
                .andExpect(jsonPath("$.status").value("PLANNED"));

        verify(orderService).getBatchById(101L);
    }

    @Test
    @DisplayName("GET /api/batches/5 - Should invoke batch lookup by numeric ID 5 and return 200 OK")
    void getBatchById_Id5_Returns200() throws Exception {
        BatchResponseDto batchFiveDto = new BatchResponseDto(
                5L,
                "BAT-2026-0001-05",
                1L,
                "MO-2026-0001",
                new BigDecimal("250.0000"),
                "kg",
                null,
                BatchStatus.PLANNED,
                LocalDate.of(2026, 3, 1),
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        when(orderService.getBatchById(5L)).thenReturn(batchFiveDto);

        mockMvc.perform(get("/api/batches/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5L))
                .andExpect(jsonPath("$.batchNumber").value("BAT-2026-0001-05"));

        verify(orderService).getBatchById(5L);
    }

    @Test
    @DisplayName("GET /api/batches/{id} - Should return 404 Not Found when ID does not exist")
    void getBatchById_NotFound_Returns404() throws Exception {
        when(orderService.getBatchById(999L))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 999"));

        mockMvc.perform(get("/api/batches/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Batch not found with id: 999"));
    }

    // -------------------------------------------------------------------------
    // 2. GET /api/batches/number/{batchNumber} - Retrieve by Batch Number
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/batches/number/{batchNumber} - Should return batch and 200 OK when found")
    void getBatchByBatchNumber_Success_Returns200() throws Exception {
        when(orderService.getBatchByBatchNumber("BAT-2026-0001-01")).thenReturn(sampleBatchDto);

        mockMvc.perform(get("/api/batches/number/BAT-2026-0001-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101L))
                .andExpect(jsonPath("$.batchNumber").value("BAT-2026-0001-01"));

        verify(orderService).getBatchByBatchNumber("BAT-2026-0001-01");
    }

    @Test
    @DisplayName("GET /api/batches/number/{batchNumber} - Should return 404 Not Found when batch number does not exist")
    void getBatchByBatchNumber_NotFound_Returns404() throws Exception {
        when(orderService.getBatchByBatchNumber("BAT-NONEXISTENT"))
                .thenThrow(new ResourceNotFoundException("Batch not found with batch number: BAT-NONEXISTENT"));

        mockMvc.perform(get("/api/batches/number/BAT-NONEXISTENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // -------------------------------------------------------------------------
    // 3. PATCH /api/batches/{id}/status - Update Batch Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /api/batches/{id}/status - Should update status to IN_PROGRESS and return 200 OK")
    void updateBatchStatus_ToInProgress_Returns200() throws Exception {
        BatchStatusUpdateDto updateDto = new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS);
        BatchResponseDto inProgressDto = new BatchResponseDto(
                101L, "BAT-2026-0001-01", 1L, "MO-2026-0001", new BigDecimal("250.0000"), "kg",
                null, BatchStatus.IN_PROGRESS, LocalDate.of(2026, 3, 1), null,
                LocalDateTime.now(), null, LocalDateTime.now(), LocalDateTime.now()
        );

        when(orderService.updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class))).thenReturn(inProgressDto);

        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startTime").exists());

        verify(orderService).updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class));
    }

    @Test
    @DisplayName("PATCH /api/batches/{id}/status - Should update status to COMPLETED with actualYield and return 200 OK")
    void updateBatchStatus_ToCompleted_Returns200() throws Exception {
        BatchStatusUpdateDto updateDto = new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("248.5000"));
        BatchResponseDto completedDto = new BatchResponseDto(
                101L, "BAT-2026-0001-01", 1L, "MO-2026-0001", new BigDecimal("250.0000"), "kg",
                new BigDecimal("248.5000"), BatchStatus.COMPLETED, LocalDate.of(2026, 3, 1), null,
                LocalDateTime.now().minusHours(2), LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now()
        );

        when(orderService.updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class))).thenReturn(completedDto);

        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.actualYield").value(248.5000))
                .andExpect(jsonPath("$.endTime").exists());

        verify(orderService).updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class));
    }

    @Test
    @DisplayName("PATCH /api/batches/{id}/status - Should return 400 Bad Request when status is null")
    void updateBatchStatus_NullStatus_Returns400() throws Exception {
        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.status").exists());

        verify(orderService, never()).updateBatchStatus(any(), any());
    }

    @Test
    @DisplayName("PATCH /api/batches/{id}/status - Should return 400 Bad Request for invalid enum value")
    void updateBatchStatus_InvalidEnum_Returns400() throws Exception {
        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"INVALID_STATUS\"}"))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).updateBatchStatus(any(), any());
    }

    @Test
    @DisplayName("PATCH /api/batches/{id}/status - Should return 400 Bad Request when actualYield is non-positive")
    void updateBatchStatus_NonPositiveYield_Returns400() throws Exception {
        BatchStatusUpdateDto invalidDto = new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("-10.0000"));

        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.actualYield").exists());

        verify(orderService, never()).updateBatchStatus(any(), any());
    }

    @Test
    @DisplayName("PATCH /api/batches/{id}/status - Should return 400 Bad Request when service throws InvalidOrderException")
    void updateBatchStatus_ServiceInvalidOrder_Returns400() throws Exception {
        BatchStatusUpdateDto updateDto = new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS);
        when(orderService.updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class)))
                .thenThrow(new InvalidOrderException("Cannot start batch because parent order is in PLANNED status"));

        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("PLANNED status")));
    }

    @Test
    @DisplayName("PATCH /api/batches/{id}/status - Should return 404 Not Found when batch ID does not exist")
    void updateBatchStatus_NotFound_Returns404() throws Exception {
        BatchStatusUpdateDto updateDto = new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS);
        when(orderService.updateBatchStatus(eq(999L), any(BatchStatusUpdateDto.class)))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 999"));

        mockMvc.perform(patch("/api/batches/999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // -------------------------------------------------------------------------
    // 4. Ignored Forged Actor & Batch Status Transition RBAC
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /api/batches/{id}/status ignores forged X-Actor-Name and JSON actorName")
    void updateBatchStatus_IgnoresForgedActorName() throws Exception {
        BatchResponseDto cancelledDto = new BatchResponseDto(
                101L, "BAT-2026-0001-01", 1L, "MO-2026-0001", new BigDecimal("250.0000"), "kg",
                null, BatchStatus.CANCELLED, LocalDate.of(2026, 3, 1), null,
                null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(orderService.updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class)))
                .thenReturn(cancelledDto);

        mockMvc.perform(patch("/api/batches/101/status")
                        .header("X-Actor-Name", "Forged Scheduler")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\",\"actorName\":\"Forged Line Supervisor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(orderService).updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class));
    }

    @Test
    @WithMockUser(username = "operator.user", roles = "OPERATOR")
    @DisplayName("OPERATOR can start/complete batches but receives 403 Forbidden on batch CANCELLED")
    void operatorBatchTransitionMatrix() throws Exception {
        BatchResponseDto inProgressDto = new BatchResponseDto(
                101L, "BAT-2026-0001-01", 1L, "MO-2026-0001", new BigDecimal("250.0000"), "kg",
                null, BatchStatus.IN_PROGRESS, LocalDate.of(2026, 3, 1), null,
                LocalDateTime.now(), null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(orderService.updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class))).thenReturn(inProgressDto);

        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isForbidden());
        verify(orderService, times(1)).updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class));
    }

    @Test
    @WithMockUser(username = "admin.user", roles = "ADMIN")
    @DisplayName("ADMIN can cancel batch but receives 403 Forbidden on IN_PROGRESS and COMPLETED transitions")
    void adminBatchTransitionMatrix() throws Exception {
        BatchResponseDto cancelledDto = new BatchResponseDto(
                101L, "BAT-2026-0001-01", 1L, "MO-2026-0001", new BigDecimal("250.0000"), "kg",
                null, BatchStatus.CANCELLED, LocalDate.of(2026, 3, 1), null,
                null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(orderService.updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class))).thenReturn(cancelledDto);

        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/batches/101/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("250.0000")))))
                .andExpect(status().isForbidden());

        verify(orderService, times(1)).updateBatchStatus(eq(101L), any(BatchStatusUpdateDto.class));
    }
}

