package com.systemdesign.scheduler.strategy.assignment;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.Worker;

import java.util.List;
import java.util.Optional;

// Strategy Pattern (GoF) — determines which worker gets a task
public interface TaskAssignmentStrategy {

    Optional<Worker> assignTask(Task task, List<Worker> availableWorkers);

    String getStrategyName();
}
