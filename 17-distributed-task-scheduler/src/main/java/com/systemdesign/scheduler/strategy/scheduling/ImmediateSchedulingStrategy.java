package com.systemdesign.scheduler.strategy.scheduling;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskStatus;
import com.systemdesign.scheduler.model.TaskType;

import java.time.Instant;
import java.util.Optional;

// Wiring: Fires immediately for ONE_TIME tasks that are still pending or queued.
// Used as the default strategy when no schedule or delay is specified.
public class ImmediateSchedulingStrategy implements SchedulingStrategy {

    @Override
    public boolean shouldScheduleNow(Task task, Instant currentTime) {
        // 1. Only applies to ONE_TIME tasks
        if (task.getTaskType() != TaskType.ONE_TIME) {
            return false;
        }
        // 2. Only schedule tasks that are PENDING or QUEUED
        TaskStatus status = task.getStatus();
        return status == TaskStatus.PENDING || status == TaskStatus.QUEUED;
    }

    @Override
    public Optional<Instant> getNextScheduleTime(Task task, Instant currentTime) {
        // Immediate tasks always fire now
        return Optional.of(Instant.now());
    }

    @Override
    public String getStrategyName() {
        return "IMMEDIATE";
    }
}
