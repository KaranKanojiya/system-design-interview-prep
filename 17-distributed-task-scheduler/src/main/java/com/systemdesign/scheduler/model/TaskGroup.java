package com.systemdesign.scheduler.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A logical group of tasks that can be executed in parallel or sequentially.
 * Used for batch submissions and workflow orchestration.
 */
public class TaskGroup {

    private final String id;
    private final String name;
    private final List<String> taskIds;
    private final boolean parallel;
    private final Instant createdAt;

    public TaskGroup(String name, boolean parallel) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.taskIds = new ArrayList<>();
        this.parallel = parallel;
        this.createdAt = Instant.now();
    }

    // --- Task management ---

    public void addTask(String taskId) {
        taskIds.add(taskId);
    }

    public void removeTask(String taskId) {
        taskIds.remove(taskId);
    }

    public int size() {
        return taskIds.size();
    }

    // --- Getters ---

    public String getId() { return id; }
    public String getName() { return name; }
    public List<String> getTaskIds() { return Collections.unmodifiableList(taskIds); }
    public boolean isParallel() { return parallel; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "TaskGroup{id='" + id + "', name='" + name
                + "', tasks=" + taskIds.size()
                + ", parallel=" + parallel + "}";
    }
}
