package com.systemdesign.gateway.strategy.auth;

import com.systemdesign.gateway.model.AuthResult;
import com.systemdesign.gateway.model.HttpRequest;

/**
 * Strategy Pattern (GoF) — determines how requests are authenticated.
 *
 * Flow: HttpRequest → AuthStrategy validates credentials → AuthResult (success/failure)
 *
 * Implementations:
 *   1. JwtAuthStrategy   — validates Bearer JWT tokens
 *   2. ApiKeyAuthStrategy — validates X-API-Key header against a known key set
 */
public interface AuthStrategy {

    /**
     * Authenticates the incoming request.
     *
     * @param request the HTTP request containing authentication credentials
     * @return AuthResult indicating success (with principal/roles) or failure (with reason)
     */
    AuthResult authenticate(HttpRequest request);

    /**
     * Human-readable name for logging and metrics.
     */
    String getStrategyName();
}
