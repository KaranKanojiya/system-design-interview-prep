package com.systemdesign.observability.repository;

// Wiring: InMemoryAlertRepository stores Alerts in a ConcurrentHashMap.
// Implements AlertRepository for the in-memory demo/testing path.

import com.systemdesign.observability.model.Alert;
import com.systemdesign.observability.model.AlertStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link AlertRepository} backed by ConcurrentHashMap.
 */
public class InMemoryAlertRepository implements AlertRepository {

    private final Map<String, Alert> store = new ConcurrentHashMap<>();

    @Override
    public void save(Alert alert) {
        store.put(alert.getId(), alert);
    }

    @Override
    public Optional<Alert> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Alert> findByStatus(AlertStatus status) {
        return store.values().stream()
                .filter(a -> a.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<Alert> findByRuleName(String ruleName) {
        return store.values().stream()
                .filter(a -> a.getRule().getName().equals(ruleName))
                .collect(Collectors.toList());
    }

    @Override
    public List<Alert> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public List<Alert> findFiring() {
        return findByStatus(AlertStatus.FIRING);
    }
}
