package com.pharmatrack.dto;

import java.math.BigDecimal;

/**
 * High-level Inventory & Warehouse KPI summary response DTO.
 */
public class InventorySummaryDto {

    private int totalMaterialsCount;
    private int activeMaterialsCount;
    private int inStockMaterialsCount;
    private int lowStockMaterialsCount;
    private int outOfStockMaterialsCount;
    private int warehouseCount;
    private int activeWarehouseCount;
    private int storageLocationCount;
    private int activeLotCount;
    private int quarantinedLotCount;
    private int expiringSoonLotCount;
    private int expiredLotCount;
    private BigDecimal totalAvailableUnits;
    private long totalMovementsCount;

    public InventorySummaryDto() {
    }

    public int getTotalMaterialsCount() {
        return totalMaterialsCount;
    }

    public void setTotalMaterialsCount(int totalMaterialsCount) {
        this.totalMaterialsCount = totalMaterialsCount;
    }

    public int getActiveMaterialsCount() {
        return activeMaterialsCount;
    }

    public void setActiveMaterialsCount(int activeMaterialsCount) {
        this.activeMaterialsCount = activeMaterialsCount;
    }

    public int getInStockMaterialsCount() {
        return inStockMaterialsCount;
    }

    public void setInStockMaterialsCount(int inStockMaterialsCount) {
        this.inStockMaterialsCount = inStockMaterialsCount;
    }

    public int getLowStockMaterialsCount() {
        return lowStockMaterialsCount;
    }

    public void setLowStockMaterialsCount(int lowStockMaterialsCount) {
        this.lowStockMaterialsCount = lowStockMaterialsCount;
    }

    public int getOutOfStockMaterialsCount() {
        return outOfStockMaterialsCount;
    }

    public void setOutOfStockMaterialsCount(int outOfStockMaterialsCount) {
        this.outOfStockMaterialsCount = outOfStockMaterialsCount;
    }

    public int getWarehouseCount() {
        return warehouseCount;
    }

    public void setWarehouseCount(int warehouseCount) {
        this.warehouseCount = warehouseCount;
    }

    public int getActiveWarehouseCount() {
        return activeWarehouseCount;
    }

    public void setActiveWarehouseCount(int activeWarehouseCount) {
        this.activeWarehouseCount = activeWarehouseCount;
    }

    public int getStorageLocationCount() {
        return storageLocationCount;
    }

    public void setStorageLocationCount(int storageLocationCount) {
        this.storageLocationCount = storageLocationCount;
    }

    public int getActiveLotCount() {
        return activeLotCount;
    }

    public void setActiveLotCount(int activeLotCount) {
        this.activeLotCount = activeLotCount;
    }

    public int getQuarantinedLotCount() {
        return quarantinedLotCount;
    }

    public void setQuarantinedLotCount(int quarantinedLotCount) {
        this.quarantinedLotCount = quarantinedLotCount;
    }

    public int getExpiringSoonLotCount() {
        return expiringSoonLotCount;
    }

    public void setExpiringSoonLotCount(int expiringSoonLotCount) {
        this.expiringSoonLotCount = expiringSoonLotCount;
    }

    public int getExpiredLotCount() {
        return expiredLotCount;
    }

    public void setExpiredLotCount(int expiredLotCount) {
        this.expiredLotCount = expiredLotCount;
    }

    public BigDecimal getTotalAvailableUnits() {
        return totalAvailableUnits;
    }

    public void setTotalAvailableUnits(BigDecimal totalAvailableUnits) {
        this.totalAvailableUnits = totalAvailableUnits;
    }

    public long getTotalMovementsCount() {
        return totalMovementsCount;
    }

    public void setTotalMovementsCount(long totalMovementsCount) {
        this.totalMovementsCount = totalMovementsCount;
    }
}

