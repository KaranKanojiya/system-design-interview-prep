package com.systemdesign.scheduler.exception;

import java.util.Collections;
import java.util.List;

// Wiring: Thrown by DependencyResolver when a cycle is detected in the
// task dependency DAG. Carries the list of involved task IDs so the caller
// can report which tasks form the cycle.
public class DependencyCycleException extends SchedulerException {

    private final List<String> involvedTaskIds;

    public DependencyCycleException(String message) {
        super(message);
        this.involvedTaskIds = Collections.emptyList();
    }

    public DependencyCycleException(String message, List<String> involvedTaskIds) {
        super(message);
        this.involvedTaskIds = Collections.unmodifiableList(involvedTaskIds);
    }

    public List<String> getInvolvedTaskIds() {
        return involvedTaskIds;
    }
}
