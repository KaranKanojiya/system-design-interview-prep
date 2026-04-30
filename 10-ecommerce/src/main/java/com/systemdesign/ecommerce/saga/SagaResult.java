package com.systemdesign.ecommerce.saga;

import java.util.ArrayList;
import java.util.List;

/**
 * SagaResult — Outcome of a saga execution.
 *
 * Captures:
 * - Whether the saga succeeded end-to-end
 * - Which steps completed successfully
 * - Which step failed (if any)
 * - Which steps were compensated (rolled back)
 * - A human-readable summary message
 *
 * Builder pattern because the orchestrator populates fields incrementally
 * as it progresses through the steps.
 */
public class SagaResult {

    private final boolean success;
    private final List<String> completedSteps;
    private final String failedStep;          // null on success
    private final List<String> compensatedSteps;
    private final String message;

    private SagaResult(Builder builder) {
        this.success = builder.success;
        this.completedSteps = builder.completedSteps;
        this.failedStep = builder.failedStep;
        this.compensatedSteps = builder.compensatedSteps;
        this.message = builder.message;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public boolean isSuccess()              { return success; }
    public List<String> getCompletedSteps() { return completedSteps; }
    public String getFailedStep()           { return failedStep; }
    public List<String> getCompensatedSteps() { return compensatedSteps; }
    public String getMessage()              { return message; }

    @Override
    public String toString() {
        return String.format("SagaResult{success=%s, completed=%s, failed='%s', compensated=%s, msg='%s'}",
                success, completedSteps, failedStep, compensatedSteps, message);
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static class Builder {
        private boolean success;
        private List<String> completedSteps = new ArrayList<>();
        private String failedStep;
        private List<String> compensatedSteps = new ArrayList<>();
        private String message;

        public Builder success(boolean s)                      { this.success = s; return this; }
        public Builder completedSteps(List<String> steps)      { this.completedSteps = steps; return this; }
        public Builder addCompletedStep(String step)           { this.completedSteps.add(step); return this; }
        public Builder failedStep(String step)                 { this.failedStep = step; return this; }
        public Builder compensatedSteps(List<String> steps)    { this.compensatedSteps = steps; return this; }
        public Builder addCompensatedStep(String step)         { this.compensatedSteps.add(step); return this; }
        public Builder message(String msg)                     { this.message = msg; return this; }

        public SagaResult build() {
            return new SagaResult(this);
        }
    }
}
