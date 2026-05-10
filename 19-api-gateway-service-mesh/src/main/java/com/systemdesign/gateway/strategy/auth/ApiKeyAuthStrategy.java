package com.systemdesign.gateway.strategy.auth;

import com.systemdesign.gateway.model.AuthResult;
import com.systemdesign.gateway.model.HttpRequest;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticates requests using an API key passed in the "X-API-Key" header.
 *
 * Flow:
 *   1. Read "X-API-Key" header from the request
 *   2. Look up the key in the configurable validKeys map (apiKey → clientName)
 *   3. If found, return AuthResult.success with clientName as the principal
 *   4. If missing or invalid, return AuthResult.unauthorized
 *
 * // wiring: plugged into AuthenticationFilter when API-key auth mode is selected
 */
public class ApiKeyAuthStrategy implements AuthStrategy {

    private final Map<String, String> validKeys;  // apiKey → clientName

    /**
     * @param validKeys maps valid API keys to their associated client names
     */
    public ApiKeyAuthStrategy(Map<String, String> validKeys) {
        this.validKeys = Objects.requireNonNull(validKeys, "validKeys must not be null");
    }

    @Override
    public AuthResult authenticate(HttpRequest request) {
        // Step 1: read the API key header
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            return AuthResult.unauthorized("Missing X-API-Key header");
        }

        // Step 2: validate against known keys
        String clientName = validKeys.get(apiKey);
        if (clientName == null) {
            return AuthResult.unauthorized("Invalid API key");
        }

        // Step 3: return success with clientName as principal
        System.out.printf("[API_KEY_AUTH] Authenticated client='%s'%n", clientName);
        return AuthResult.success(clientName, Set.of("API_CLIENT"));
    }

    @Override
    public String getStrategyName() {
        return "API_KEY";
    }
}
