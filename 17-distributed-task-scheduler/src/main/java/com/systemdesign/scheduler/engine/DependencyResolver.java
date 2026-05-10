package com.systemdesign.scheduler.engine;

import com.systemdesign.scheduler.exception.DependencyCycleException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

// Wiring: DAG-based dependency tracker consumed by SchedulerEngine.
// Before a task is dispatched, SchedulerEngine calls getReadyTasks() to check
// whether all upstream dependencies have completed. Uses Kahn's algorithm for
// topological ordering and DFS coloring for cycle detection.
public class DependencyResolver {

    // taskId -> set of taskIds it depends on (i.e., must complete before this task runs)
    private final Map<String, Set<String>> dependencies;

    public DependencyResolver() {
        this.dependencies = new HashMap<>();
    }

    // Registers: taskId depends on dependsOn (dependsOn must finish first)
    public void addDependency(String taskId, String dependsOn) {
        dependencies.computeIfAbsent(taskId, k -> new HashSet<>()).add(dependsOn);
        // Ensure dependsOn also exists in the graph as a node (even if it has no deps)
        dependencies.putIfAbsent(dependsOn, new HashSet<>());
    }

    public void removeDependency(String taskId, String dependsOn) {
        Set<String> deps = dependencies.get(taskId);
        if (deps != null) {
            deps.remove(dependsOn);
            if (deps.isEmpty()) {
                dependencies.remove(taskId);
            }
        }
    }

    // Returns task IDs whose ALL dependencies appear in completedTaskIds
    public Set<String> getReadyTasks(Set<String> completedTaskIds) {
        Set<String> ready = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            String taskId = entry.getKey();
            // Skip tasks that are already completed
            if (completedTaskIds.contains(taskId)) {
                continue;
            }
            // A task is ready when every dependency is in the completed set
            if (completedTaskIds.containsAll(entry.getValue())) {
                ready.add(taskId);
            }
        }
        return ready;
    }

    // Returns direct dependencies for a given task
    public Set<String> getDependencies(String taskId) {
        return dependencies.getOrDefault(taskId, Collections.emptySet());
    }

    // --- Cycle detection via DFS three-coloring (WHITE=unvisited, GRAY=in-stack, BLACK=done) ---

    private static final int WHITE = 0;
    private static final int GRAY = 1;
    private static final int BLACK = 2;

    public boolean hasCycle() {
        Map<String, Integer> color = new HashMap<>();
        for (String node : dependencies.keySet()) {
            color.put(node, WHITE);
        }
        for (String node : dependencies.keySet()) {
            if (color.get(node) == WHITE) {
                if (dfsCycleCheck(node, color)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean dfsCycleCheck(String node, Map<String, Integer> color) {
        color.put(node, GRAY);
        for (String dep : dependencies.getOrDefault(node, Collections.emptySet())) {
            Integer depColor = color.getOrDefault(dep, WHITE);
            if (depColor == GRAY) {
                // Back edge — cycle found
                return true;
            }
            if (depColor == WHITE && dfsCycleCheck(dep, color)) {
                return true;
            }
        }
        color.put(node, BLACK);
        return false;
    }

    // --- Topological sort via Kahn's algorithm ---

    public List<String> getTopologicalOrder() {
        if (hasCycle()) {
            throw new DependencyCycleException("Dependency graph contains a cycle — topological sort impossible");
        }

        // 1. Build in-degree map and adjacency (dependsOn -> dependents)
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();

        for (String node : dependencies.keySet()) {
            inDegree.putIfAbsent(node, 0);
            dependents.putIfAbsent(node, new HashSet<>());
        }
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            String taskId = entry.getKey();
            for (String dep : entry.getValue()) {
                // dep -> taskId (dep must run before taskId)
                dependents.computeIfAbsent(dep, k -> new HashSet<>()).add(taskId);
                inDegree.merge(taskId, 1, Integer::sum);
            }
        }

        // 2. Seed the queue with zero-in-degree nodes
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        // 3. BFS — peel off zero-in-degree nodes
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            order.add(node);
            for (String dependent : dependents.getOrDefault(node, Collections.emptySet())) {
                int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(dependent);
                }
            }
        }

        return order;
    }
}
