package com.systemdesign.scheduler.repository;

import com.systemdesign.scheduler.model.Worker;
import com.systemdesign.scheduler.model.WorkerStatus;

import java.util.List;
import java.util.Optional;

// Wiring: Storage abstraction for Worker nodes in the cluster.
// Used by the load-balancing strategy to find available workers for dispatch.
public interface WorkerRepository {

    void save(Worker worker);

    Optional<Worker> findById(String id);

    List<Worker> findByStatus(WorkerStatus status);

    // Returns workers that are ACTIVE and have spare capacity (currentLoad < capacity)
    List<Worker> findAvailable();

    List<Worker> findAll();

    void deleteById(String id);
}
