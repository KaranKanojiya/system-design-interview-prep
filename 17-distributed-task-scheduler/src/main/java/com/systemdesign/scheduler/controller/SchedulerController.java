package com.systemdesign.scheduler.controller;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskStatus;
import com.systemdesign.scheduler.service.MonitoringService;
import com.systemdesign.scheduler.service.SchedulerService;

import java.util.List;
import java.util.Optional;

// Wiring: REST-like facade that delegates to SchedulerService and MonitoringService.
// Simulates HTTP endpoints for demo purposes — each method logs the equivalent
// HTTP method + path before delegating. Created by AppConfig as the top-level entry point.
public class SchedulerController {

    private final SchedulerService schedulerService;
    private final MonitoringService monitoringService;

    public SchedulerController(SchedulerService schedulerService, MonitoringService monitoringService) {
        this.schedulerService = schedulerService;
        this.monitoringService = monitoringService;
    }

    // POST /tasks — submit a new task for scheduling
    public Task submitTask(Task task) {
        System.out.println("[CONTROLLER] POST /tasks — submitting task: " + task.getName());
        return schedulerService.submitTask(task);
    }

    // POST /tasks/with-deps — submit a task with dependency list
    public Task submitTaskWithDeps(Task task, List<String> deps) {
        System.out.println("[CONTROLLER] POST /tasks/with-deps — submitting task: "
                + task.getName() + " with " + deps.size() + " dependencies");
        return schedulerService.submitTaskWithDependencies(task, deps);
    }

    // DELETE /tasks/{id} — cancel a task
    public void cancelTask(String taskId) {
        System.out.println("[CONTROLLER] DELETE /tasks/" + taskId + " — cancelling task");
        schedulerService.cancelTask(taskId);
    }

    // GET /tasks/{id}/status — get formatted task status
    public String getTaskStatus(String taskId) {
        System.out.println("[CONTROLLER] GET /tasks/" + taskId + "/status");
        Optional<TaskStatus> status = schedulerService.getTaskStatus(taskId);
        return status.map(s -> "Task " + taskId + " status: " + s)
                .orElse("Task " + taskId + " not found");
    }

    // POST /scheduler/dispatch — trigger one scheduling cycle
    public void runSchedulingCycle() {
        System.out.println("[CONTROLLER] POST /scheduler/dispatch — running scheduling cycle");
        schedulerService.scheduleAndDispatch();
    }

    // GET /monitoring/dashboard — print the monitoring dashboard
    public void getDashboard() {
        System.out.println("[CONTROLLER] GET /monitoring/dashboard");
        monitoringService.printDashboard();
    }
}
