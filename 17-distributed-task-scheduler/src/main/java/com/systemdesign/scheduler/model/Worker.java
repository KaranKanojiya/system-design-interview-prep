package com.systemdesign.scheduler.model;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Wiring: Represents a worker node that pulls and executes tasks.
// Registered with the Scheduler, monitored via heartbeats,
// and selected by the LoadBalancer based on capacity and affinity tags.
public class Worker {

    private final String id;
    private final String hostname;
    private final int port;
    private final int capacity;
    private int currentLoad;
    private WorkerStatus status;
    private Instant lastHeartbeat;
    private final Instant registeredAt;
    private final Set<String> tags;

    public Worker(String hostname, int port, int capacity) {
        this(UUID.randomUUID().toString(), hostname, port, capacity);
    }

    public Worker(String id, String hostname, int port, int capacity) {
        this.id = id;
        this.hostname = hostname;
        this.port = port;
        this.capacity = capacity;
        this.currentLoad = 0;
        this.status = WorkerStatus.ACTIVE;
        this.lastHeartbeat = Instant.now();
        this.registeredAt = Instant.now();
        this.tags = new HashSet<>();
    }

    public Worker(String hostname, int port, int capacity, Set<String> tags) {
        this(hostname, port, capacity);
        this.tags.addAll(tags);
    }

    // --- Load management ---

    public void incrementLoad() {
        this.currentLoad++;
        if (currentLoad >= capacity) {
            this.status = WorkerStatus.BUSY;
        }
    }

    public void decrementLoad() {
        if (currentLoad > 0) {
            this.currentLoad--;
        }
        if (status == WorkerStatus.BUSY && currentLoad < capacity) {
            this.status = WorkerStatus.ACTIVE;
        }
    }

    public boolean isAvailable() {
        return status == WorkerStatus.ACTIVE && currentLoad < capacity;
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
    }

    // --- Tag management ---

    public void addTag(String tag) {
        this.tags.add(tag);
    }

    public boolean hasTag(String tag) {
        return this.tags.contains(tag);
    }

    // --- Getters ---

    public String getId() { return id; }
    public String getHostname() { return hostname; }
    public int getPort() { return port; }
    public int getCapacity() { return capacity; }
    public int getCurrentLoad() { return currentLoad; }
    public WorkerStatus getStatus() { return status; }
    public void setStatus(WorkerStatus status) { this.status = status; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Set<String> getTags() { return tags; }

    @Override
    public String toString() {
        return "Worker{id='" + id + "', host='" + hostname + ":" + port
                + "', load=" + currentLoad + "/" + capacity
                + ", status=" + status + "}";
    }
}
