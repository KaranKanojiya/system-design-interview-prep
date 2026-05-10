package com.systemdesign.gateway.strategy.auth;

import com.systemdesign.gateway.model.AuthResult;
import com.systemdesign.gateway.model.HttpRequest;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Simulates JWT (JSON Web Token) authentication for the API gateway.
 *
 * Flow:
 *   1. Check "Authorization" header exists and starts with "Bearer "
 *   2. Extract the token and validate structure (3 dot-separated parts)
 *   3. Decode the simulated payload to extract the subject
 *   4. Look up roles for the subject in the configurable roleMap
 *   5. Return AuthResult.success with principal and roles, or AuthResult.unauthorized
 *
 * Note: This is a simulation for system design study — real JWT validation would
 * verify signatures, check expiry, and use a proper JWT library.
 *
 * // wiring: plugged into AuthenticationFilter as the default AuthStrategy
 */
public class JwtAuthStrategy implements AuthStrategy {

    private final Map<String, Set<String>> roleMap;  // subject → roles

    /**
     * @param roleMap maps JWT subject (username/service) to their granted roles
     */
    public JwtAuthStrategy(Map<String, Set<String>> roleMap) {
        this.roleMap = Objects.requireNonNull(roleMap, "roleMap must not be null");
    }

    @Override
    public AuthResult authenticate(HttpRequest request) {
        // Step 1: check Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return AuthResult.unauthorized("Missing or invalid Authorization header");
        }

        // Step 2: extract and validate token structure
        String token = authHeader.substring("Bearer ".length()).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return AuthResult.unauthorized("Invalid JWT format — expected 3 parts, got " + parts.length);
        }

        // Step 3: decode the simulated payload (middle part) to extract subject
        String subject = extractSubject(parts[1]);
        if (subject == null || subject.isBlank()) {
            return AuthResult.unauthorized("Could not extract subject from JWT payload");
        }

        // Step 4: look up roles
        Set<String> roles = roleMap.getOrDefault(subject, Set.of());

        System.out.printf("[JWT_AUTH] Authenticated subject='%s' with roles=%s%n", subject, roles);
        return AuthResult.success(subject, roles);
    }

    @Override
    public String getStrategyName() {
        return "JWT";
    }

    // ── Private helpers ──

    /**
     * Simulates extracting the "sub" field from a base64-encoded JWT payload.
     * In real implementation this would parse JSON; here we treat the decoded payload as the subject.
     */
    private String extractSubject(String encodedPayload) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encodedPayload);
            String payload = new String(decoded);
            // Simplified: if payload contains "sub":" extract it; otherwise use whole payload
            int subIndex = payload.indexOf("\"sub\":\"");
            if (subIndex >= 0) {
                int start = subIndex + "\"sub\":\"".length();
                int end = payload.indexOf("\"", start);
                return end > start ? payload.substring(start, end) : payload.substring(start);
            }
            // Fallback: treat entire decoded payload as the subject
            return payload.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
