package com.systemdesign.observability.repository;

// Wiring: TraceRepository persists assembled Trace objects.
// Used by TracingService (write) and QueryService (read).

import com.systemdesign.observability.model.Trace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for storing and querying distributed traces.
 */
public interface TraceRepository {

    void save(Trace trace);

    Optional<Trace> findById(String traceId);

    List<Trace> findByServiceName(String serviceName);

    List<Trace> findByTimeRange(Instant from, Instant to);

    List<Trace> findAll();
}
