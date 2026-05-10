package com.systemdesign.gateway.service;

import com.systemdesign.gateway.model.ServiceMeshConfig;
import com.systemdesign.gateway.engine.TlsEngine;
import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.HttpResponse;
import com.systemdesign.gateway.model.ServiceInstance;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service mesh sidecar proxy simulation — handles service-to-service communication.
 *
 * Flow: callerService → (1) mTLS validation → (2) circuit breaker check → (3) load balance
 *       → (4) forward request → (5) record result → (6) return response
 *
 * Pattern: simulates an Envoy/Istio-style sidecar proxy managing cross-cutting concerns.
 */
public class ServiceMeshService {

    private final TlsEngine tlsEngine;                         // wiring: mutual TLS validation engine
    private final CircuitBreakerService circuitBreakerService;  // wiring: circuit breaker for target services
    private final LoadBalancerService loadBalancerService;      // wiring: instance selection for target services
    private final ServiceMeshConfig meshConfig;                 // wiring: mesh-level configuration

    public ServiceMeshService(TlsEngine tlsEngine,
                              CircuitBreakerService circuitBreakerService,
                              LoadBalancerService loadBalancerService,
                              ServiceMeshConfig meshConfig) {
        this.tlsEngine = Objects.requireNonNull(tlsEngine, "tlsEngine must not be null");
        this.circuitBreakerService = Objects.requireNonNull(circuitBreakerService, "circuitBreakerService must not be null");
        this.loadBalancerService = Objects.requireNonNull(loadBalancerService, "loadBalancerService must not be null");
        this.meshConfig = Objects.requireNonNull(meshConfig, "meshConfig must not be null");
    }

    /**
     * Proxies a service-to-service request through the sidecar, applying the full mesh pipeline:
     *   1. Validate mTLS if enabled
     *   2. Check circuit breaker
     *   3. Select instance via load balancer
     *   4. Forward request (simulated with random latency)
     *   5. Record success/failure in circuit breaker
     *   6. Return response
     *
     * @param callerService  the originating service name
     * @param targetService  the destination service name
     * @param request        the HTTP request to proxy
     * @return the HTTP response from the target service (or an error response)
     */
    public HttpResponse proxyRequest(String callerService, String targetService, HttpRequest request) {
        System.out.println("[SERVICE MESH] ─── Sidecar proxy: %s → %s ───".formatted(callerService, targetService));

        // Step 1: mTLS validation
        if (meshConfig.isMtlsEnabled()) {
            boolean tlsValid = tlsEngine.validateConnection(callerService, targetService);
            System.out.println("[SERVICE MESH] Step 1: mTLS validation — %s".formatted(
                    tlsValid ? "PASSED" : "FAILED"));
            if (!tlsValid) {
                return new HttpResponse.Builder(403)
                        .body("{\"error\":\"mTLS validation failed between %s and %s\"}".formatted(
                                callerService, targetService))
                        .serviceName(targetService)
                        .build();
            }
        } else {
            System.out.println("[SERVICE MESH] Step 1: mTLS disabled — skipping");
        }

        // Step 2: Circuit breaker check
        boolean circuitAllowed = circuitBreakerService.allowRequest(targetService);
        System.out.println("[SERVICE MESH] Step 2: Circuit breaker — %s".formatted(
                circuitAllowed ? "CLOSED (allowing)" : "OPEN (blocking)"));
        if (!circuitAllowed) {
            return new HttpResponse.Builder(503)
                    .body("{\"error\":\"Circuit breaker OPEN for service '%s'\"}".formatted(targetService))
                    .serviceName(targetService)
                    .build();
        }

        // Step 3: Load balancer — select instance
        Optional<ServiceInstance> instance = loadBalancerService.selectInstance(targetService, request);
        if (instance.isEmpty()) {
            System.out.println("[SERVICE MESH] Step 3: No healthy instance available for '%s'".formatted(targetService));
            circuitBreakerService.recordFailure(targetService);
            return new HttpResponse.Builder(503)
                    .body("{\"error\":\"No healthy instances for service '%s'\"}".formatted(targetService))
                    .serviceName(targetService)
                    .build();
        }
        System.out.println("[SERVICE MESH] Step 3: Selected instance %s".formatted(instance.get().getAddress()));

        // Step 4: Simulate request forwarding (random latency 5-50ms, 5% failure chance)
        long latencyMs = ThreadLocalRandom.current().nextLong(5, 51);
        boolean requestFailed = ThreadLocalRandom.current().nextDouble() < 0.05;

        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("[SERVICE MESH] Step 4: Forwarded to %s — latency=%dms, success=%s".formatted(
                instance.get().getAddress(), latencyMs, !requestFailed));

        // Step 5: Record result in circuit breaker
        if (requestFailed) {
            circuitBreakerService.recordFailure(targetService);
            System.out.println("[SERVICE MESH] Step 5: Recorded FAILURE for '%s'".formatted(targetService));
            return new HttpResponse.Builder(502)
                    .body("{\"error\":\"Service '%s' returned an error\"}".formatted(targetService))
                    .latencyMs(latencyMs)
                    .serviceName(targetService)
                    .build();
        }

        circuitBreakerService.recordSuccess(targetService);
        System.out.println("[SERVICE MESH] Step 5: Recorded SUCCESS for '%s'".formatted(targetService));

        // Step 6: Build and return success response
        HttpResponse response = new HttpResponse.Builder(200)
                .body("{\"service\":\"%s\",\"instance\":\"%s\",\"message\":\"OK\"}".formatted(
                        targetService, instance.get().getAddress()))
                .header("X-Mesh-Source", callerService)
                .header("X-Mesh-Target", targetService)
                .header("X-Mesh-Instance", instance.get().getAddress())
                .latencyMs(latencyMs)
                .serviceName(targetService)
                .build();

        System.out.println("[SERVICE MESH] Step 6: Response — status=%d, latency=%dms".formatted(
                response.getStatusCode(), latencyMs));
        return response;
    }

    /**
     * Enables mutual TLS for all service-to-service communication.
     */
    public void enableMtls() {
        tlsEngine.enableMtls();
        System.out.println("[SERVICE MESH] mTLS enabled");
    }

    /**
     * Disables mutual TLS for all service-to-service communication.
     */
    public void disableMtls() {
        tlsEngine.disableMtls();
        System.out.println("[SERVICE MESH] mTLS disabled");
    }

    /**
     * Returns the current service mesh configuration.
     */
    public ServiceMeshConfig getServiceConfig() {
        return meshConfig;
    }
}
