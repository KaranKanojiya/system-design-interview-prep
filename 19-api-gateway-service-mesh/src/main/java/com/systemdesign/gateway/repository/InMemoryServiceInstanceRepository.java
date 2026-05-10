package com.systemdesign.gateway.repository;

// Wiring: InMemoryServiceInstanceRepository stores ServiceInstances in a ConcurrentHashMap.
// Implements ServiceInstanceRepository for the in-memory demo/testing path.

import com.systemdesign.gateway.model.HealthStatus;
import com.systemdesign.gateway.model.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link ServiceInstanceRepository} backed by ConcurrentHashMap.
 */
public class InMemoryServiceInstanceRepository implements ServiceInstanceRepository {

    private final Map<String, ServiceInstance> store = new ConcurrentHashMap<>();

    @Override
    public void save(ServiceInstance instance) {
        store.put(instance.getId(), instance);
    }

    @Override
    public Optional<ServiceInstance> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<ServiceInstance> findByServiceName(String serviceName) {
        return store.values().stream()
                .filter(inst -> inst.getServiceName().equals(serviceName))
                .collect(Collectors.toList());
    }

    @Override
    public List<ServiceInstance> findHealthy(String serviceName) {
        return store.values().stream()
                .filter(inst -> inst.getServiceName().equals(serviceName))
                .filter(inst -> inst.getHealthStatus() == HealthStatus.HEALTHY)
                .collect(Collectors.toList());
    }

    @Override
    public List<ServiceInstance> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }
}
