package com.pharmatrack.entity;

/**
 * Derived availability status for a raw material based on active, non-expired stock
 * relative to its configured minimum stock threshold.
 */
public enum MaterialStockStatus {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK,
    INACTIVE
}

