package com.systemdesign.scheduler.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable result of a task execution.
 * Created via static factory methods for clarity.
 */
public class TaskResult {

    private final boolean success;
    private final String output;
    private final Map<String, String> metadata;

    private TaskResult(boolean success, String output, Map<String, String> metadata) {
        this.success = success;
        this.output = output;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    // --- Static factory methods ---

    public static TaskResult success(String output) {
        return new TaskResult(true, output, Map.of());
    }

    public static TaskResult success(String output, Map<String, String> metadata) {
        return new TaskResult(true, output, metadata);
    }

    public static TaskResult failure(String error) {
        return new TaskResult(false, error, Map.of());
    }

    public static TaskResult failure(String error, Map<String, String> metadata) {
        return new TaskResult(false, error, metadata);
    }

    // --- Getters ---

    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public Map<String, String> getMetadata() { return metadata; }

    @Override
    public String toString() {
        return "TaskResult{success=" + success + ", output='" + output + "'}";
    }
}
