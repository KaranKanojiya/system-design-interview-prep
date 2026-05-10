package com.systemdesign.gateway.engine;

// Wiring: TlsEngine simulates mutual TLS (mTLS) between services in the mesh.
// Used by ServiceMeshService -> validates caller/target trust -> before allowing inter-service calls.
// Both caller and target must be in the trusted set and mTLS must be enabled.

import java.util.HashSet;
import java.util.Set;

/**
 * Simulates mutual TLS (mTLS) validation for service-to-service communication.
 * Both the caller and target must be trusted, and mTLS must be globally enabled.
 */
public class TlsEngine {

    // Set of service names trusted for mTLS communication
    private final Set<String> trustedServices = new HashSet<>();

    // Global mTLS toggle
    private boolean mtlsEnabled = false;

    /** Enables mutual TLS enforcement globally. */
    public void enableMtls() {
        this.mtlsEnabled = true;
        System.out.println("[mTLS] Mutual TLS enabled");
    }

    /** Disables mutual TLS enforcement globally. */
    public void disableMtls() {
        this.mtlsEnabled = false;
        System.out.println("[mTLS] Mutual TLS disabled");
    }

    /** Adds a service to the trusted set. */
    public void trustService(String serviceName) {
        trustedServices.add(serviceName);
        System.out.println("[mTLS] Trusted service added: " + serviceName);
    }

    /** Removes a service from the trusted set. */
    public void revokeService(String serviceName) {
        trustedServices.remove(serviceName);
        System.out.println("[mTLS] Trust revoked for service: " + serviceName);
    }

    /**
     * Validates that a connection between caller and target is allowed.
     * Both must be in the trusted set, and mTLS must be enabled.
     */
    public boolean validateConnection(String callerService, String targetService) {
        if (!mtlsEnabled) {
            System.out.println("[mTLS] Validation skipped — mTLS is disabled."
                    + " caller=" + callerService + " target=" + targetService);
            return false;
        }

        boolean callerTrusted = trustedServices.contains(callerService);
        boolean targetTrusted = trustedServices.contains(targetService);
        boolean allowed = callerTrusted && targetTrusted;

        if (allowed) {
            System.out.println("[mTLS] Connection ALLOWED: "
                    + callerService + " -> " + targetService);
        } else {
            System.out.println("[mTLS] Connection DENIED: "
                    + callerService + " -> " + targetService
                    + " (callerTrusted=" + callerTrusted
                    + ", targetTrusted=" + targetTrusted + ")");
        }

        return allowed;
    }

    /** Checks if a specific service is in the trusted set. */
    public boolean isServiceTrusted(String serviceName) {
        return trustedServices.contains(serviceName);
    }

    /** Returns an unmodifiable copy of all trusted services. */
    public Set<String> getTrustedServices() {
        return Set.copyOf(trustedServices);
    }
}
