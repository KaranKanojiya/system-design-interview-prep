package com.systemdesign.scheduler.repository;

import com.systemdesign.scheduler.model.Worker;
import com.systemdesign.scheduler.model.WorkerStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Wiring: In-memory Worker store. findAvailable() is the hot path —
// called by the dispatcher on every tick to locate workers with spare capacity.
public class InMemoryWorkerRepository implements WorkerRepository {

    private final Map<String, Worker> store = new ConcurrentHashMap<>();

    @Override
    public void save(Worker worker) {
        store.put(worker.getId(), worker);
    }

    @Override
    public Optional<Worker> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Worker> findByStatus(WorkerStatus status) {
        return store.values().stream()
                .filter(worker -> worker.getStatus() == status)
                .toList();
    }

    @Override
    public List<Worker> findAvailable() {
        // Available = ACTIVE status AND currentLoad < capacity
        return store.values().stream()
                .filter(worker -> worker.getStatus() == WorkerStatus.ACTIVE)
                .filter(worker -> worker.getCurrentLoad() < worker.getCapacity())
                .toList();
    }

    @Override
    public List<Worker> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }
}
