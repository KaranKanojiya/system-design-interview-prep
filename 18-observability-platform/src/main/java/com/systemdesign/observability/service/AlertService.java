package com.systemdesign.observability.service;

// Wiring: AlertService manages alert rule evaluation and alert lifecycle.
// Dependencies injected via constructor:
//   alertRepo        — persists fired Alert instances
//   alertingStrategy — Strategy pattern — decides whether a rule's condition is met
//   metricService    — provides recent metric data for rule evaluation

import com.systemdesign.observability.model.Alert;
import com.systemdesign.observability.model.AlertRule;
import com.systemdesign.observability.model.AlertStatus;
import com.systemdesign.observability.model.MetricPoint;
import com.systemdesign.observability.repository.AlertRepository;
import com.systemdesign.observability.strategy.alerting.AlertingStrategy;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * AlertService — business logic for alert rule evaluation and management.
 *
 * FLOW — evaluateRules():
 *   1. Iterate over all enabled AlertRules
 *   2. For each rule, query the last 60 seconds of metric data from MetricService
 *   3. Pass the data points to AlertingStrategy.shouldAlert(rule, points)
 *   4. If triggered: create an Alert with FIRING status, save to AlertRepository
 *   5. If previously firing and now OK: resolve the alert
 *   6. Print [ALERT] log for each state change
 *
 * FLOW — acknowledgeAlert(alertId):
 *   1. Find the Alert by ID in the repository
 *   2. Call alert.acknowledge() to transition to ACKNOWLEDGED status
 *
 * FLOW — resolveAlert(alertId):
 *   1. Find the Alert by ID in the repository
 *   2. Call alert.resolve() to transition to RESOLVED and stamp resolution time
 */
public class AlertService {

    private final AlertRepository alertRepo;          // persists fired alerts
    private final AlertingStrategy alertingStrategy;  // Strategy pattern — threshold evaluation
    private final MetricService metricService;        // provides metric data for evaluation

    // Local rule storage — rules are registered at runtime
    private final List<AlertRule> rules = new CopyOnWriteArrayList<>();

    // Tracks which rules are currently in FIRING state (ruleId -> alertId)
    private final Map<String, String> firingRules = new HashMap<>();

    public AlertService(AlertRepository alertRepo, AlertingStrategy alertingStrategy,
                        MetricService metricService) {
        this.alertRepo = alertRepo;
        this.alertingStrategy = alertingStrategy;
        this.metricService = metricService;
    }

    // ---- rule management ----

    /**
     * Registers an alert rule for periodic evaluation.
     *
     * @param rule the alert rule to register
     */
    public void createRule(AlertRule rule) {
        rules.add(rule);
        System.out.println("[ALERT] Created rule '" + rule.getName()
                + "' for metric '" + rule.getMetricName()
                + "' | condition: " + rule.getCondition() + " " + rule.getThreshold());
    }

    /**
     * Evaluates all enabled rules against recent metric data.
     * For each rule:
     *   - Queries the last 60 seconds of metric points
     *   - Checks if the alerting strategy indicates an alert should fire
     *   - Creates or resolves alerts based on state transitions
     */
    public void evaluateRules() {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(60);

        for (AlertRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }

            // 1. Query recent metric data
            List<MetricPoint> points = metricService.query(
                    rule.getMetricName(), windowStart, now);

            // 2. Check alerting strategy
            boolean shouldAlert = alertingStrategy.shouldAlert(rule, points);

            if (shouldAlert && !firingRules.containsKey(rule.getId())) {
                // 3a. New alert — transition from OK to FIRING
                double currentValue = points.isEmpty() ? 0.0
                        : points.get(points.size() - 1).getValue();

                Alert alert = new Alert(rule, currentValue,
                        "Rule '" + rule.getName() + "' triggered: "
                                + rule.getCondition() + " " + rule.getThreshold()
                                + " (current=" + currentValue + ")");

                alertRepo.save(alert);
                firingRules.put(rule.getId(), alert.getId());

                System.out.println("[ALERT] FIRING — rule '" + rule.getName()
                        + "' | metric='" + rule.getMetricName()
                        + "' | value=" + currentValue
                        + " | threshold=" + rule.getThreshold());

            } else if (!shouldAlert && firingRules.containsKey(rule.getId())) {
                // 3b. Condition cleared — resolve the existing alert
                String alertId = firingRules.remove(rule.getId());
                resolveAlert(alertId);

                System.out.println("[ALERT] RESOLVED — rule '" + rule.getName()
                        + "' | metric='" + rule.getMetricName() + "' recovered");
            }
        }
    }

    // ---- alert lifecycle ----

    /**
     * Acknowledges an alert — an operator has seen it but has not yet resolved it.
     *
     * @param alertId the alert ID to acknowledge
     */
    public void acknowledgeAlert(String alertId) {
        alertRepo.findById(alertId).ifPresent(alert -> {
            alert.acknowledge();
            System.out.println("[ALERT] Acknowledged alert " + alertId);
        });
    }

    /**
     * Resolves an alert — stamps resolution time and sets status to RESOLVED.
     *
     * @param alertId the alert ID to resolve
     */
    public void resolveAlert(String alertId) {
        alertRepo.findById(alertId).ifPresent(alert -> {
            alert.resolve();
            System.out.println("[ALERT] Resolved alert " + alertId);
        });
    }

    // ---- querying ----

    /**
     * Returns all alerts currently in FIRING status.
     */
    public List<Alert> getFiringAlerts() {
        return alertRepo.findAll().stream()
                .filter(a -> a.getStatus() == AlertStatus.FIRING)
                .collect(Collectors.toList());
    }

    /**
     * Returns all alerts regardless of status.
     */
    public List<Alert> getAllAlerts() {
        return alertRepo.findAll();
    }

    /**
     * Returns all registered alert rules.
     */
    public List<AlertRule> getRules() {
        return Collections.unmodifiableList(rules);
    }
}
