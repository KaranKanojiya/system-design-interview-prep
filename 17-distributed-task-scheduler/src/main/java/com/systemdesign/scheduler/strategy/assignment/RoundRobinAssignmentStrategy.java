package com.systemdesign.scheduler.strategy.assignment;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.Worker;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

// Wiring: Cycles through available workers in order using an atomic counter.
// Filters out unavailable workers first, then picks the next one by index.
// Thread-safe via AtomicInteger — safe for concurrent task assignment.
public class RoundRobinAssignmentStrategy implements TaskAssignmentStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public Optional<Worker> assignTask(Task task, List<Worker> availableWorkers) {
        // 1. Filter to only available workers
        List<Worker> ready = availableWorkers.stream()
                .filter(Worker::isAvailable)
                .toList();

        if (ready.isEmpty()) {
            return Optional.empty();
        }

        // 2. Round-robin selection via atomic counter
        int index = Math.abs(counter.getAndIncrement() % ready.size());
        return Optional.of(ready.get(index));
    }

    @Override
    public String getStrategyName() {
        return "ROUND_ROBIN";
    }
}
