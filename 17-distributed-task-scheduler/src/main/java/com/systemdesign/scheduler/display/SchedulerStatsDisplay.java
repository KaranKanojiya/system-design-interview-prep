package com.systemdesign.scheduler.display;

import com.systemdesign.scheduler.engine.DependencyResolver;
import com.systemdesign.scheduler.engine.TaskQueue;
import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskExecution;
import com.systemdesign.scheduler.model.Worker;
import com.systemdesign.scheduler.repository.TaskRepository;
import com.systemdesign.scheduler.repository.WorkerRepository;
import com.systemdesign.scheduler.service.MonitoringService;

import java.util.List;
import java.util.Set;

// Wiring: Console output helper used by the demo main() to display formatted
// tables of tasks, workers, executions, and dependency graphs.
// Reads from repositories and MonitoringService — never mutates state.
public class SchedulerStatsDisplay {

    private final MonitoringService monitoringService;
    private final TaskRepository taskRepo;
    private final WorkerRepository workerRepo;

    public SchedulerStatsDisplay(MonitoringService monitoringService,
                                  TaskRepository taskRepo,
                                  WorkerRepository workerRepo) {
        this.monitoringService = monitoringService;
        this.taskRepo = taskRepo;
        this.workerRepo = workerRepo;
    }

    // Prints a table of all tasks: id (first 8 chars), name, type, status, priority
    public void printTaskSummary() {
        List<Task> tasks = taskRepo.findAll();
        System.out.printf("%-12s %-20s %-12s %-12s %-10s%n",
                "ID", "NAME", "TYPE", "STATUS", "PRIORITY");
        System.out.println("-".repeat(70));
        for (Task task : tasks) {
            System.out.printf("%-12s %-20s %-12s %-12s %-10s%n",
                    truncateId(task.getId()),
                    task.getName(),
                    task.getTaskType(),
                    task.getStatus(),
                    task.getPriority());
        }
        System.out.println();
    }

    // Prints a table of all workers: id (first 8 chars), hostname, load/capacity, status
    public void printWorkerPool() {
        List<Worker> workers = workerRepo.findAll();
        System.out.printf("%-12s %-20s %-15s %-10s%n",
                "ID", "HOSTNAME", "LOAD/CAPACITY", "STATUS");
        System.out.println("-".repeat(60));
        for (Worker worker : workers) {
            System.out.printf("%-12s %-20s %-15s %-10s%n",
                    truncateId(worker.getId()),
                    worker.getHostname(),
                    worker.getCurrentLoad() + "/" + worker.getCapacity(),
                    worker.getStatus());
        }
        System.out.println();
    }

    // Prints all execution records for a given task
    public void printExecutionHistory(String taskId, List<TaskExecution> executions) {
        System.out.printf("Execution history for task %s:%n", truncateId(taskId));
        System.out.printf("%-12s %-12s %-8s %-12s %-15s%n",
                "EXEC_ID", "WORKER", "ATTEMPT", "STATUS", "DURATION");
        System.out.println("-".repeat(65));
        for (TaskExecution exec : executions) {
            System.out.printf("%-12s %-12s %-8d %-12s %-15s%n",
                    truncateId(exec.getId()),
                    truncateId(exec.getWorkerId()),
                    exec.getAttemptNumber(),
                    exec.getStatus(),
                    exec.getDuration().toMillis() + "ms");
        }
        System.out.println();
    }

    // Prints the current state of the task queue
    public void printQueueStatus(TaskQueue queue) {
        System.out.println("Queue size: " + queue.size());
        List<Task> tasks = queue.getAllTasks();
        if (tasks.isEmpty()) {
            System.out.println("  (empty)");
        } else {
            System.out.printf("  %-12s %-20s %-10s%n", "ID", "NAME", "PRIORITY");
            System.out.println("  " + "-".repeat(45));
            for (Task task : tasks) {
                System.out.printf("  %-12s %-20s %-10s%n",
                        truncateId(task.getId()),
                        task.getName(),
                        task.getPriority());
            }
        }
        System.out.println();
    }

    // Prints an ASCII representation of the dependency DAG
    public void printDependencyGraph(DependencyResolver resolver, List<Task> tasks) {
        System.out.println("Dependency Graph (task -> depends on):");
        System.out.println("-".repeat(50));
        for (Task task : tasks) {
            Set<String> deps = resolver.getDependencies(task.getId());
            String taskLabel = truncateId(task.getId()) + " (" + task.getName() + ")";
            if (deps.isEmpty()) {
                System.out.println("  " + taskLabel + " -> (no dependencies)");
            } else {
                for (String depId : deps) {
                    System.out.println("  " + taskLabel + " -> " + truncateId(depId));
                }
            }
        }
        System.out.println();
    }

    // Prints final summary stats from MonitoringService
    public void printStats() {
        printSeparator("FINAL STATS");
        monitoringService.printDashboard();
    }

    // Prints a standard demo separator banner
    public void printSeparator(String title) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("  " + title);
        System.out.println("=".repeat(70));
        System.out.println();
    }

    // Truncates an ID to first 8 characters + "..."
    private String truncateId(String id) {
        if (id == null) {
            return "null";
        }
        if (id.length() <= 8) {
            return id;
        }
        return id.substring(0, 8) + "...";
    }
}
