package com.systemdesign.observability.repository;

// Wiring: MetricRepository persists Metric objects.
// Used by MetricIngestionService (write) and QueryService (read).

import com.systemdesign.observability.model.Metric;
import com.systemdesign.observability.model.MetricType;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for storing and querying metric definitions.
 */
public interface MetricRepository {

    void save(Metric metric);

    Optional<Metric> findById(String id);

    List<Metric> findByName(String name);

    List<Metric> findByType(MetricType type);

    List<Metric> findAll();

    void deleteById(String id);
}
