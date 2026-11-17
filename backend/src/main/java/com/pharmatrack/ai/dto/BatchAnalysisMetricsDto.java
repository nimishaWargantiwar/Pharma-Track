package com.pharmatrack.ai.dto;

import java.math.BigDecimal;

/**
 * Deterministic manufacturing KPIs and verification metrics calculated directly
 * from authoritative MES records, strictly separated from LLM-generated prose.
 */
public class BatchAnalysisMetricsDto {

    private BigDecimal plannedBatchSize;
    private BigDecimal actualYield;
    private BigDecimal yieldPercent;
    private BigDecimal yieldVariance;
    private Integer executionStepCount;
    private Integer qcCheckCount;
    private Integer deviationCount;

    public BatchAnalysisMetricsDto() {
    }

    public BatchAnalysisMetricsDto(BigDecimal plannedBatchSize,
                                  BigDecimal actualYield,
                                  BigDecimal yieldPercent,
                                  BigDecimal yieldVariance,
                                  Integer executionStepCount,
                                  Integer qcCheckCount,
                                  Integer deviationCount) {
        this.plannedBatchSize = plannedBatchSize;
        this.actualYield = actualYield;
        this.yieldPercent = yieldPercent;
        this.yieldVariance = yieldVariance;
        this.executionStepCount = executionStepCount;
        this.qcCheckCount = qcCheckCount;
        this.deviationCount = deviationCount;
    }

    public BigDecimal getPlannedBatchSize() {
        return plannedBatchSize;
    }

    public void setPlannedBatchSize(BigDecimal plannedBatchSize) {
        this.plannedBatchSize = plannedBatchSize;
    }

    public BigDecimal getActualYield() {
        return actualYield;
    }

    public void setActualYield(BigDecimal actualYield) {
        this.actualYield = actualYield;
    }

    public BigDecimal getYieldPercent() {
        return yieldPercent;
    }

    public void setYieldPercent(BigDecimal yieldPercent) {
        this.yieldPercent = yieldPercent;
    }

    public BigDecimal getYieldVariance() {
        return yieldVariance;
    }

    public void setYieldVariance(BigDecimal yieldVariance) {
        this.yieldVariance = yieldVariance;
    }

    public Integer getExecutionStepCount() {
        return executionStepCount;
    }

    public void setExecutionStepCount(Integer executionStepCount) {
        this.executionStepCount = executionStepCount;
    }

    public Integer getQcCheckCount() {
        return qcCheckCount;
    }

    public void setQcCheckCount(Integer qcCheckCount) {
        this.qcCheckCount = qcCheckCount;
    }

    public Integer getDeviationCount() {
        return deviationCount;
    }

    public void setDeviationCount(Integer deviationCount) {
        this.deviationCount = deviationCount;
    }
}
