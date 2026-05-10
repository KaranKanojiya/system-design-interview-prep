package com.systemdesign.scheduler.strategy.scheduling;

import com.systemdesign.scheduler.model.Task;

import java.time.Instant;
import java.util.Optional;

// Strategy Pattern (GoF) — determines when a task should be scheduled
public interface SchedulingStrategy {

    boolean shouldScheduleNow(Task task, Instant currentTime);

    Optional<Instant> getNextScheduleTime(Task task, Instant currentTime);

    String getStrategyName();
}
