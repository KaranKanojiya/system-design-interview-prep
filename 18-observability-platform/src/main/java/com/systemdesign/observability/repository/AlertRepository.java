package com.systemdesign.observability.repository;

// Wiring: AlertRepository persists Alert objects.
// Used by AlertingService (write) and QueryService/NotificationService (read).

import com.systemdesign.observability.model.Alert;
import com.systemdesign.observability.model.AlertStatus;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for storing and querying alert instances.
 */
public interface AlertRepository {

    void save(Alert alert);

    Optional<Alert> findById(String id);

    List<Alert> findByStatus(AlertStatus status);

    List<Alert> findByRuleName(String ruleName);

    List<Alert> findAll();

    /** Returns all alerts currently in FIRING status. */
    List<Alert> findFiring();
}
