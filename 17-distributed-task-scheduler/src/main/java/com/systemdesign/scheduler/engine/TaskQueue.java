package com.systemdesign.scheduler.engine;

import com.systemdesign.scheduler.model.Task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

// Wiring: Priority queue that feeds SchedulerEngine.tick() with the next task to dispatch.
// Sort order: higher priority first (CRITICAL > HIGH > MEDIUM > LOW), then earlier createdAt first.
// Not thread-safe — consumed only by the single-threaded scheduler loop.
public class TaskQueue {

    // Comparator: descending priority value, then ascending createdAt
    private static final Comparator<Task> TASK_COMPARATOR = Comparator
            .comparingInt((Task t) -> t.getPriority().getValue())
            .reversed()
            .thenComparing(Task::getCreatedAt);

    private final PriorityQueue<Task> queue;

    public TaskQueue() {
        this.queue = new PriorityQueue<>(TASK_COMPARATOR);
    }

    // Adds a task to the priority queue
    public void enqueue(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("Task must not be null");
        }
        queue.offer(task);
    }

    // Removes and returns the highest-priority task, or empty if queue is drained
    public Optional<Task> dequeue() {
        return Optional.ofNullable(queue.poll());
    }

    // Returns the highest-priority task without removing it
    public Optional<Task> peek() {
        return Optional.ofNullable(queue.peek());
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    // Removes a specific task by ID — scans the queue linearly (acceptable for interview-scale data)
    public boolean remove(String taskId) {
        return queue.removeIf(task -> task.getId().equals(taskId));
    }

    // Returns a snapshot of all queued tasks (sorted by priority) for inspection
    public List<Task> getAllTasks() {
        List<Task> tasks = new ArrayList<>(queue);
        tasks.sort(TASK_COMPARATOR);
        return tasks;
    }
}
