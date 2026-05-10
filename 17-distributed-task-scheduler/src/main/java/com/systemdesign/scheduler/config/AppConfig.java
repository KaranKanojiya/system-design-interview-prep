package com.systemdesign.scheduler.config;

import com.systemdesign.scheduler.controller.SchedulerController;
import com.systemdesign.scheduler.display.SchedulerStatsDisplay;
import com.systemdesign.scheduler.engine.CronParser;
import com.systemdesign.scheduler.engine.DependencyResolver;
import com.systemdesign.scheduler.engine.SchedulerEngine;
import com.systemdesign.scheduler.engine.TaskQueue;
import com.systemdesign.scheduler.repository.ExecutionRepository;
import com.systemdesign.scheduler.repository.InMemoryExecutionRepository;
import com.systemdesign.scheduler.repository.InMemorySchedulerNodeRepository;
import com.systemdesign.scheduler.repository.InMemoryTaskRepository;
import com.systemdesign.scheduler.repository.InMemoryWorkerRepository;
import com.systemdesign.scheduler.repository.SchedulerNodeRepository;
import com.systemdesign.scheduler.repository.TaskRepository;
import com.systemdesign.scheduler.repository.WorkerRepository;
import com.systemdesign.scheduler.service.ExecutionService;
import com.systemdesign.scheduler.service.FailoverService;
import com.systemdesign.scheduler.service.LeaderElectionService;
import com.systemdesign.scheduler.service.MonitoringService;
import com.systemdesign.scheduler.service.SchedulerService;
import com.systemdesign.scheduler.service.TaskService;
import com.systemdesign.scheduler.service.WorkerService;
import com.systemdesign.scheduler.strategy.assignment.RoundRobinAssignmentStrategy;
import com.systemdesign.scheduler.strategy.assignment.TaskAssignmentStrategy;
import com.systemdesign.scheduler.strategy.retry.ExponentialBackoffRetryStrategy;
import com.systemdesign.scheduler.strategy.retry.RetryStrategy;
import com.systemdesign.scheduler.strategy.scheduling.ImmediateSchedulingStrategy;
import com.systemdesign.scheduler.strategy.scheduling.SchedulingStrategy;

// Factory Pattern + Composition Root — single wiring point for all dependencies.
// No DI framework. Each getter lazily creates and wires its dependency graph.
// Strategy setters allow demos to swap assignment, retry, and scheduling strategies.
public class AppConfig {

    // --- Repositories (lazily initialized) ---
    private TaskRepository taskRepository;
    private WorkerRepository workerRepository;
    private ExecutionRepository executionRepository;
    private SchedulerNodeRepository schedulerNodeRepository;

    // --- Engine components ---
    private CronParser cronParser;
    private TaskQueue taskQueue;
    private DependencyResolver dependencyResolver;
    private SchedulerEngine schedulerEngine;

    // --- Strategies (swappable) ---
    private TaskAssignmentStrategy assignmentStrategy;
    private RetryStrategy retryStrategy;
    private SchedulingStrategy schedulingStrategy;

    // --- Services ---
    private TaskService taskService;
    private WorkerService workerService;
    private ExecutionService executionService;
    private LeaderElectionService leaderElectionService;
    private FailoverService failoverService;
    private MonitoringService monitoringService;
    private SchedulerService schedulerService;

    // --- Controller & Display ---
    private SchedulerController schedulerController;
    private SchedulerStatsDisplay schedulerStatsDisplay;

    // ===== Repository getters =====

    public TaskRepository getTaskRepository() {
        if (taskRepository == null) {
            taskRepository = new InMemoryTaskRepository();
        }
        return taskRepository;
    }

    public WorkerRepository getWorkerRepository() {
        if (workerRepository == null) {
            workerRepository = new InMemoryWorkerRepository();
        }
        return workerRepository;
    }

    public ExecutionRepository getExecutionRepository() {
        if (executionRepository == null) {
            executionRepository = new InMemoryExecutionRepository();
        }
        return executionRepository;
    }

    public SchedulerNodeRepository getSchedulerNodeRepository() {
        if (schedulerNodeRepository == null) {
            schedulerNodeRepository = new InMemorySchedulerNodeRepository();
        }
        return schedulerNodeRepository;
    }

    // ===== Engine getters =====

    public CronParser getCronParser() {
        if (cronParser == null) {
            cronParser = new CronParser();
        }
        return cronParser;
    }

    public TaskQueue getTaskQueue() {
        if (taskQueue == null) {
            taskQueue = new TaskQueue();
        }
        return taskQueue;
    }

    public DependencyResolver getDependencyResolver() {
        if (dependencyResolver == null) {
            dependencyResolver = new DependencyResolver();
        }
        return dependencyResolver;
    }

    public SchedulerEngine getSchedulerEngine() {
        if (schedulerEngine == null) {
            schedulerEngine = new SchedulerEngine(
                    getTaskQueue(),
                    getDependencyResolver(),
                    getCronParser()
            );
        }
        return schedulerEngine;
    }

    // ===== Strategy getters =====

    public TaskAssignmentStrategy getAssignmentStrategy() {
        if (assignmentStrategy == null) {
            assignmentStrategy = new RoundRobinAssignmentStrategy();
        }
        return assignmentStrategy;
    }

    public RetryStrategy getRetryStrategy() {
        if (retryStrategy == null) {
            retryStrategy = new ExponentialBackoffRetryStrategy(1000, 30000, 2.0);
        }
        return retryStrategy;
    }

    public SchedulingStrategy getSchedulingStrategy() {
        if (schedulingStrategy == null) {
            schedulingStrategy = new ImmediateSchedulingStrategy();
        }
        return schedulingStrategy;
    }

    // ===== Strategy setters (swap and clear dependents) =====

    public void setAssignmentStrategy(TaskAssignmentStrategy strategy) {
        this.assignmentStrategy = strategy;
        // Clear dependents so they get re-created with the new strategy
        this.schedulerService = null;
        this.failoverService = null;
        this.schedulerController = null;
    }

    public void setRetryStrategy(RetryStrategy strategy) {
        this.retryStrategy = strategy;
        this.executionService = null;
        this.schedulerService = null;
        this.schedulerController = null;
    }

    public void setSchedulingStrategy(SchedulingStrategy strategy) {
        this.schedulingStrategy = strategy;
        this.schedulerService = null;
        this.schedulerController = null;
    }

    // ===== Service getters =====

    public TaskService getTaskService() {
        if (taskService == null) {
            taskService = new TaskService(getTaskRepository(), getExecutionRepository());
        }
        return taskService;
    }

    public WorkerService getWorkerService() {
        if (workerService == null) {
            workerService = new WorkerService(getWorkerRepository());
        }
        return workerService;
    }

    public ExecutionService getExecutionService() {
        if (executionService == null) {
            executionService = new ExecutionService(
                    getTaskRepository(),
                    getExecutionRepository(),
                    getWorkerRepository(),
                    getRetryStrategy()
            );
        }
        return executionService;
    }

    public LeaderElectionService getLeaderElectionService() {
        if (leaderElectionService == null) {
            leaderElectionService = new LeaderElectionService(getSchedulerNodeRepository());
        }
        return leaderElectionService;
    }

    public FailoverService getFailoverService() {
        if (failoverService == null) {
            failoverService = new FailoverService(
                    getWorkerService(),
                    getTaskService(),
                    getExecutionRepository(),
                    getAssignmentStrategy()
            );
        }
        return failoverService;
    }

    public MonitoringService getMonitoringService() {
        if (monitoringService == null) {
            monitoringService = new MonitoringService(
                    getTaskRepository(),
                    getWorkerRepository(),
                    getExecutionRepository()
            );
        }
        return monitoringService;
    }

    public SchedulerService getSchedulerService() {
        if (schedulerService == null) {
            schedulerService = new SchedulerService(
                    getTaskService(),
                    getWorkerService(),
                    getExecutionService(),
                    getSchedulerEngine(),
                    getAssignmentStrategy(),
                    getSchedulingStrategy()
            );
        }
        return schedulerService;
    }

    // ===== Controller & Display getters =====

    public SchedulerController getSchedulerController() {
        if (schedulerController == null) {
            schedulerController = new SchedulerController(
                    getSchedulerService(),
                    getMonitoringService()
            );
        }
        return schedulerController;
    }

    public SchedulerStatsDisplay getStatsDisplay() {
        if (schedulerStatsDisplay == null) {
            schedulerStatsDisplay = new SchedulerStatsDisplay(
                    getMonitoringService(),
                    getTaskRepository(),
                    getWorkerRepository()
            );
        }
        return schedulerStatsDisplay;
    }
}
