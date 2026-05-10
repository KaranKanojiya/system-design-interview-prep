package com.systemdesign.gateway.repository;

// Wiring: ServiceInstanceRepository persists ServiceInstance objects.
// Used by ServiceRegistry (write) and LoadBalancerService (read).

import com.systemdesign.gateway.model.ServiceInstance;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for storing and querying service instances.
 */
public interface ServiceInstanceRepository {

    void save(ServiceInstance instance);

    Optional<ServiceInstance> findById(String id);

    List<ServiceInstance> findByServiceName(String serviceName);

    List<ServiceInstance> findHealthy(String serviceName);

    List<ServiceInstance> findAll();

    void deleteById(String id);
}
