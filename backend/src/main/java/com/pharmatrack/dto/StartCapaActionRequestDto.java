package com.pharmatrack.dto;

import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for starting work on an {@code OPEN} CAPA action.
 */
public class StartCapaActionRequestDto {

    @Size(max = 1000, message = "Notes must not exceed 1000 characters")
    private String notes;

    public StartCapaActionRequestDto() {
    }

    public StartCapaActionRequestDto(String notes) {
        this.notes = notes;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

