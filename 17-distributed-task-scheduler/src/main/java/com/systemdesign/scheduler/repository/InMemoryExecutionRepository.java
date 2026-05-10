package com.systemdesign.scheduler.repository;

import com.systemdesign.scheduler.model.TaskExecution;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Wiring: In-memory execution history store. findLatestByTaskId() sorts by startTime
// descending to return the most recent run — used by retry logic to check last failure.
public class InMemoryExecutionRepository implements ExecutionRepository {

    private final Map<String, TaskExecution> store = new ConcurrentHashMap<>();

    @Override
    public void save(TaskExecution execution) {
        store.put(execution.getId(), execution);
    }

    @Override
    public Optional<TaskExecution> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<TaskExecution> findByTaskId(String taskId) {
        return store.values().stream()
                .filter(exec -> exec.getTaskId().equals(taskId))
                .toList();
    }

    @Override
    public List<TaskExecution> findByWorkerId(String workerId) {
        return store.values().stream()
                .filter(exec -> exec.getWorkerId().equals(workerId))
                .toList();
    }

    @Override
    public Optional<TaskExecution> findLatestByTaskId(String taskId) {
        return store.values().stream()
                .filter(exec -> exec.getTaskId().equals(taskId))
                .max(Comparator.comparing(TaskExecution::getStartTime));
    }

    @Override
    public List<TaskExecution> findAll() {
        return List.copyOf(store.values());
    }
}
