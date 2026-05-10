package com.systemdesign.gateway.repository;

// Wiring: InMemoryRouteRepository stores Routes in a ConcurrentHashMap.
// Implements RouteRepository for the in-memory demo/testing path.

import com.systemdesign.gateway.model.Route;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link RouteRepository} backed by ConcurrentHashMap.
 */
public class InMemoryRouteRepository implements RouteRepository {

    private final Map<String, Route> store = new ConcurrentHashMap<>();

    @Override
    public void save(Route route) {
        store.put(route.getId(), route);
    }

    @Override
    public Optional<Route> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Route> findByPathPattern(String pattern) {
        return store.values().stream()
                .filter(r -> r.getPathPattern().equals(pattern))
                .collect(Collectors.toList());
    }

    @Override
    public List<Route> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }
}
