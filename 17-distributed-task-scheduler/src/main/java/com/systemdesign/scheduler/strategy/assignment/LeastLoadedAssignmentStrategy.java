package com.systemdesign.scheduler.strategy.assignment;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.Worker;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Wiring: Selects the worker with the lowest currentLoad among available workers.
// Ties are broken by registeredAt (oldest worker preferred, as it is likely
// the most stable). This achieves even load distribution across the cluster.
public class LeastLoadedAssignmentStrategy implements TaskAssignmentStrategy {

    @Override
    public Optional<Worker> assignTask(Task task, List<Worker> availableWorkers) {
        return availableWorkers.stream()
                .filter(Worker::isAvailable)
                // 1. Sort by currentLoad ascending
                // 2. Break ties by registeredAt ascending (prefer older workers)
                .min(Comparator.comparingInt(Worker::getCurrentLoad)
                        .thenComparing(Worker::getRegisteredAt));
    }

    @Override
    public String getStrategyName() {
        return "LEAST_LOADED";
    }
}
