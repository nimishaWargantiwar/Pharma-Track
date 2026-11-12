package com.pharmatrack.dto;

import com.pharmatrack.entity.CapaType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request payload DTO for adding a Corrective or Preventive Action ({@code CAPA}) to a deviation.
 */
public class CreateCapaActionRequestDto {

    @NotNull(message = "CAPA type is mandatory")
    private CapaType capaType;

    @NotBlank(message = "CAPA title is mandatory")
    @Size(max = 200, message = "CAPA title must not exceed 200 characters")
    private String title;

    @NotBlank(message = "CAPA action plan is mandatory")
    @Size(max = 2000, message = "CAPA action plan must not exceed 2000 characters")
    private String actionPlan;

    @NotBlank(message = "Action owner name is mandatory")
    @Size(max = 100, message = "Action owner name must not exceed 100 characters")
    private String ownerName;

    @NotNull(message = "Due date is mandatory")
    private LocalDate dueDate;

    public CreateCapaActionRequestDto() {
    }

    public CreateCapaActionRequestDto(CapaType capaType, String title, String actionPlan,
                                      String ownerName, LocalDate dueDate) {
        this.capaType = capaType;
        this.title = title;
        this.actionPlan = actionPlan;
        this.ownerName = ownerName;
        this.dueDate = dueDate;
    }

    public CapaType getCapaType() {
        return capaType;
    }

    public void setCapaType(CapaType capaType) {
        this.capaType = capaType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getActionPlan() {
        return actionPlan;
    }

    public void setActionPlan(String actionPlan) {
        this.actionPlan = actionPlan;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }
}

