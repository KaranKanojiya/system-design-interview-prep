package com.systemdesign.scheduler.repository;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Wiring: In-memory implementation backed by ConcurrentHashMap.
// Thread-safe for concurrent reads/writes from scheduler and worker threads.
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<String, Task> store = new ConcurrentHashMap<>();

    @Override
    public void save(Task task) {
        store.put(task.getId(), task);
    }

    @Override
    public Optional<Task> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Task> findByStatus(TaskStatus status) {
        return store.values().stream()
                .filter(task -> task.getStatus() == status)
                .toList();
    }

    @Override
    public List<Task> findByGroupId(String groupId) {
        return store.values().stream()
                .filter(task -> groupId.equals(task.getGroupId()))
                .toList();
    }

    @Override
    public List<Task> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }

    @Override
    public boolean existsById(String id) {
        return store.containsKey(id);
    }
}
