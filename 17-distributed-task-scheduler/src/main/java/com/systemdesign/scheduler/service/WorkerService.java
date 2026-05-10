package com.systemdesign.scheduler.service;

import com.systemdesign.scheduler.model.Worker;
import com.systemdesign.scheduler.model.WorkerStatus;
import com.systemdesign.scheduler.repository.WorkerRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

// Wiring: Worker pool management service.
// Tracks worker registration, heartbeats, and liveness.
// Delegates persistence to WorkerRepository.
public class WorkerService {

    private final WorkerRepository workerRepo;  // worker CRUD storage

    public WorkerService(WorkerRepository workerRepo) {
        this.workerRepo = workerRepo;
    }

    // 1. Registers a new worker in the pool
    public void registerWorker(Worker worker) {
        workerRepo.save(worker);
        System.out.println("[WORKER SERVICE] Registered worker: " + worker);
    }

    // 2. Marks a worker as OFFLINE (graceful deregistration)
    public void deregisterWorker(String workerId) {
        workerRepo.findById(workerId).ifPresent(worker -> {
            worker.setStatus(WorkerStatus.OFFLINE);
            workerRepo.save(worker);
            System.out.println("[WORKER SERVICE] Deregistered worker: " + workerId);
        });
    }

    // 3. Returns all workers with available capacity
    public List<Worker> getAvailableWorkers() {
        return workerRepo.findAvailable();
    }

    // 4. Updates the heartbeat timestamp for a worker
    public void updateHeartbeat(String workerId) {
        workerRepo.findById(workerId).ifPresent(worker -> {
            worker.updateHeartbeat();
            workerRepo.save(worker);
        });
    }

    // 5. Marks a worker as DEAD (detected by failover service)
    public void markWorkerDead(String workerId) {
        workerRepo.findById(workerId).ifPresent(worker -> {
            worker.setStatus(WorkerStatus.DEAD);
            workerRepo.save(worker);
            System.out.println("[WORKER SERVICE] Worker marked DEAD: " + workerId);
        });
    }

    // 6. Fetches a worker by ID
    public Optional<Worker> getWorker(String id) {
        return workerRepo.findById(id);
    }

    // 7. Returns all workers in the pool
    public List<Worker> getAllWorkers() {
        return workerRepo.findAll();
    }

    // 8. Checks if a worker's heartbeat is within the allowed timeout
    public boolean isWorkerAlive(String workerId, Duration timeout) {
        return workerRepo.findById(workerId)
                .map(worker -> {
                    Duration sinceLastHeartbeat = Duration.between(worker.getLastHeartbeat(), Instant.now());
                    return sinceLastHeartbeat.compareTo(timeout) < 0;
                })
                .orElse(false);
    }
}
