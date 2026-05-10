package com.systemdesign.scheduler.strategy.scheduling;

import com.systemdesign.scheduler.model.Task;

import java.time.Instant;
import java.util.Optional;

// Wiring: Computes the fire time as createdAt + delayMillis (or uses scheduledAt
// if explicitly set). Holds the task until the computed fire time is reached,
// then allows scheduling.
public class DelayedSchedulingStrategy implements SchedulingStrategy {

    @Override
    public boolean shouldScheduleNow(Task task, Instant currentTime) {
        Instant fireTime = computeFireTime(task);
        if (fireTime == null) {
            return false;
        }
        // Schedule when current time is at or past the fire time
        return !currentTime.isBefore(fireTime);
    }

    @Override
    public Optional<Instant> getNextScheduleTime(Task task, Instant currentTime) {
        Instant fireTime = computeFireTime(task);
        return Optional.ofNullable(fireTime);
    }

    @Override
    public String getStrategyName() {
        return "DELAYED";
    }

    /**
     * Determines the fire time for a delayed task.
     * If scheduledAt is set, use it directly; otherwise compute createdAt + delayMillis.
     */
    private Instant computeFireTime(Task task) {
        // Prefer explicit scheduledAt if set
        if (task.getScheduledAt() != null) {
            return task.getScheduledAt();
        }
        // Fall back to createdAt + delayMillis
        long delayMillis = task.getDelayMillis();
        if (delayMillis <= 0) {
            return null;
        }
        return task.getCreatedAt().plusMillis(delayMillis);
    }
}
