package com.systemdesign.scheduler.model;

import java.util.Objects;

/**
 * Represents a directed edge in the task dependency DAG.
 * "taskId depends on dependsOnTaskId" means dependsOnTaskId must complete
 * before taskId can be scheduled.
 */
public class TaskDependency {

    private final String taskId;
    private final String dependsOnTaskId;

    public TaskDependency(String taskId, String dependsOnTaskId) {
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.dependsOnTaskId = Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
    }

    public String getTaskId() { return taskId; }
    public String getDependsOnTaskId() { return dependsOnTaskId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskDependency that = (TaskDependency) o;
        return taskId.equals(that.taskId) && dependsOnTaskId.equals(that.dependsOnTaskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, dependsOnTaskId);
    }

    @Override
    public String toString() {
        return "TaskDependency{" + taskId + " -> " + dependsOnTaskId + "}";
    }
}
