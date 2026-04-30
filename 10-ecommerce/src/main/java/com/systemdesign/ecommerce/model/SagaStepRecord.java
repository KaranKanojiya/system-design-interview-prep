package com.systemdesign.ecommerce.model;

import java.time.LocalDateTime;

/**
 * SagaStepRecord — Audit trail for one step in a distributed saga.
 *
 * Interview notes:
 * - Every saga step (reserve inventory, process payment, create shipment)
 *   produces a SagaStepRecord so we can trace exactly what happened, when,
 *   and whether compensation was needed.
 * - The SagaStepStatus sub-enum captures the full lifecycle of a step:
 *     PENDING → COMPLETED   (happy path)
 *     PENDING → FAILED      (step itself failed, may trigger compensation of earlier steps)
 *     COMPLETED → COMPENSATING → COMPENSATED  (earlier success rolled back)
 */
public class SagaStepRecord {

    /**
     * Status enum for individual saga steps.
     */
    public enum SagaStepStatus {
        PENDING,
        COMPLETED,
        COMPENSATING,
        COMPENSATED,
        FAILED
    }

    private final String stepName;
    private SagaStepStatus status;
    private final LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public SagaStepRecord(String stepName) {
        this.stepName = stepName;
        this.status = SagaStepStatus.PENDING;
        this.startedAt = LocalDateTime.now();
    }

    // ── Mutations (called by the saga orchestrator) ──────────────────────

    public void markCompleted() {
        this.status = SagaStepStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = SagaStepStatus.FAILED;
        this.completedAt = LocalDateTime.now();
    }

    public void markCompensating() {
        this.status = SagaStepStatus.COMPENSATING;
    }

    public void markCompensated() {
        this.status = SagaStepStatus.COMPENSATED;
        this.completedAt = LocalDateTime.now();
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getStepName()          { return stepName; }
    public SagaStepStatus getStatus()    { return status; }
    public LocalDateTime getStartedAt()  { return startedAt; }
    public LocalDateTime getCompletedAt(){ return completedAt; }

    @Override
    public String toString() {
        return String.format("SagaStep{name='%s', status=%s}", stepName, status);
    }
}
