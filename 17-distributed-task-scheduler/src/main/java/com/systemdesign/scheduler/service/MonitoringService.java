package com.systemdesign.scheduler.service;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskExecution;
import com.systemdesign.scheduler.model.TaskStatus;
import com.systemdesign.scheduler.model.Worker;
import com.systemdesign.scheduler.repository.ExecutionRepository;
import com.systemdesign.scheduler.repository.TaskRepository;
import com.systemdesign.scheduler.repository.WorkerRepository;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Wiring: Monitoring service that computes and displays scheduler metrics.
// Reads from TaskRepository, WorkerRepository, and ExecutionRepository.
// No writes — purely observational.
public class MonitoringService {

    private final TaskRepository taskRepo;        // task status counts
    private final WorkerRepository workerRepo;    // worker utilization data
    private final ExecutionRepository execRepo;   // execution timing and outcome data

    public MonitoringService(TaskRepository taskRepo, WorkerRepository workerRepo,
                             ExecutionRepository execRepo) {
        this.taskRepo = taskRepo;
        this.workerRepo = workerRepo;
        this.execRepo = execRepo;
    }

    // 1. Counts tasks grouped by their current status
    public Map<TaskStatus, Long> getTaskCountByStatus() {
        Map<TaskStatus, Long> counts = new HashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            counts.put(status, 0L);
        }
        for (Task task : taskRepo.findAll()) {
            counts.merge(task.getStatus(), 1L, Long::sum);
        }
        return counts;
    }

    // 2. Computes worker utilization as load/capacity ratio per worker
    public Map<String, Double> getWorkerUtilization() {
        Map<String, Double> utilization = new HashMap<>();
        for (Worker worker : workerRepo.findAll()) {
            double ratio = worker.getCapacity() > 0
                    ? (double) worker.getCurrentLoad() / worker.getCapacity()
                    : 0.0;
            utilization.put(worker.getId(), ratio);
        }
        return utilization;
    }

    // 3. Computes average execution time across all completed executions
    public Duration getAverageExecutionTime() {
        List<TaskExecution> executions = execRepo.findAll();
        long totalMillis = 0;
        int completedCount = 0;

        for (TaskExecution exec : executions) {
            if (exec.getStatus() == TaskStatus.COMPLETED && exec.getDuration() != Duration.ZERO) {
                totalMillis += exec.getDuration().toMillis();
                completedCount++;
            }
        }

        if (completedCount == 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(totalMillis / completedCount);
    }

    // 4. Computes failure rate: failed executions / total executions
    public double getFailureRate() {
        List<TaskExecution> executions = execRepo.findAll();
        if (executions.isEmpty()) {
            return 0.0;
        }
        long failedCount = executions.stream()
                .filter(exec -> exec.getStatus() == TaskStatus.FAILED)
                .count();
        return (double) failedCount / executions.size();
    }

    // 5. Computes retry rate: retried executions (attempt > 1) / total executions
    public double getRetryRate() {
        List<TaskExecution> executions = execRepo.findAll();
        if (executions.isEmpty()) {
            return 0.0;
        }
        long retriedCount = executions.stream()
                .filter(exec -> exec.getAttemptNumber() > 1)
                .count();
        return (double) retriedCount / executions.size();
    }

    // 6. Returns the count of completed tasks
    public long getThroughput() {
        return taskRepo.findByStatus(TaskStatus.COMPLETED).size();
    }

    // 7. Prints a formatted dashboard of all scheduler metrics
    public void printDashboard() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("       SCHEDULER MONITORING DASHBOARD   ");
        System.out.println("========================================");

        // Task counts by status
        Map<TaskStatus, Long> statusCounts = getTaskCountByStatus();
        System.out.println();
        System.out.println("  Task Status Breakdown:");
        for (Map.Entry<TaskStatus, Long> entry : statusCounts.entrySet()) {
            if (entry.getValue() > 0) {
                System.out.printf("    %-12s : %d%n", entry.getKey(), entry.getValue());
            }
        }

        // Worker utilization
        Map<String, Double> utilization = getWorkerUtilization();
        System.out.println();
        System.out.println("  Worker Utilization:");
        for (Map.Entry<String, Double> entry : utilization.entrySet()) {
            String id = entry.getKey().length() > 12
                    ? entry.getKey().substring(0, 8) + "..."
                    : entry.getKey();
            System.out.printf("    %-12s : %.1f%%%n", id, entry.getValue() * 100);
        }

        // Execution metrics
        System.out.println();
        System.out.println("  Execution Metrics:");
        System.out.printf("    Avg Execution Time : %dms%n", getAverageExecutionTime().toMillis());
        System.out.printf("    Failure Rate       : %.1f%%%n", getFailureRate() * 100);
        System.out.printf("    Retry Rate         : %.1f%%%n", getRetryRate() * 100);
        System.out.printf("    Throughput         : %d completed tasks%n", getThroughput());

        System.out.println();
        System.out.println("========================================");
    }
}
