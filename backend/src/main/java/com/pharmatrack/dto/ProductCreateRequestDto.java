package com.pharmatrack.dto;

import com.pharmatrack.entity.ProductStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

/**
 * Dedicated request payload DTO for creating a Pharmaceutical Product.
 * Rejects any caller-supplied {@code id} so creation cannot trigger JPA merge/overwrite behavior.
 */
public class ProductCreateRequestDto {

    @Null(message = "Product ID must not be provided when creating a product")
    private Long id;

    @NotBlank(message = "Product code is mandatory")
    @Size(max = 50, message = "Product code must not exceed 50 characters")
    private String productCode;

    @NotBlank(message = "Product name is mandatory")
    @Size(max = 255, message = "Product name must not exceed 255 characters")
    private String productName;

    @NotBlank(message = "Strength is mandatory")
    @Size(max = 100, message = "Strength must not exceed 100 characters")
    private String strength;

    @NotBlank(message = "Dosage form is mandatory")
    @Size(max = 100, message = "Dosage form must not exceed 100 characters")
    private String dosageForm;

    private String description;

    @NotNull(message = "Status is mandatory")
    private ProductStatus status;

    public ProductCreateRequestDto() {
    }

    public ProductCreateRequestDto(String productCode,
                                   String productName,
                                   String strength,
                                   String dosageForm,
                                   String description,
                                   ProductStatus status) {
        this(null, productCode, productName, strength, dosageForm, description, status);
    }

    public ProductCreateRequestDto(Long id,
                                   String productCode,
                                   String productName,
                                   String strength,
                                   String dosageForm,
                                   String description,
                                   ProductStatus status) {
        this.id = id;
        this.productCode = productCode;
        this.productName = productName;
        this.strength = strength;
        this.dosageForm = dosageForm;
        this.description = description;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getStrength() {
        return strength;
    }

    public void setStrength(String strength) {
        this.strength = strength;
    }

    public String getDosageForm() {
        return dosageForm;
    }

    public void setDosageForm(String dosageForm) {
        this.dosageForm = dosageForm;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public void setStatus(ProductStatus status) {
        this.status = status;
    }
}

