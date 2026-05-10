package com.systemdesign.gateway.repository;

// Wiring: RouteRepository persists Route objects.
// Used by RouteService (write) and RequestRouter (read via service layer).

import com.systemdesign.gateway.model.Route;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for storing and querying gateway route definitions.
 */
public interface RouteRepository {

    void save(Route route);

    Optional<Route> findById(String id);

    List<Route> findByPathPattern(String pattern);

    List<Route> findAll();

    void deleteById(String id);
}
