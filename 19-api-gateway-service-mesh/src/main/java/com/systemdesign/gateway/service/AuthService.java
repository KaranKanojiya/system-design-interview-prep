package com.systemdesign.gateway.service;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.Route;
import com.systemdesign.gateway.model.AuthResult;
import com.systemdesign.gateway.strategy.auth.AuthStrategy;

import java.util.Objects;

/**
 * Authentication and authorization service — validates identity and enforces access control.
 *
 * Flow: HttpRequest → authenticate() verifies identity → authorize() checks role against route metadata
 *
 * Pattern: Strategy (GoF) — the auth mechanism (JWT, API key, OAuth) is swappable at runtime.
 */
public class AuthService {

    private volatile AuthStrategy authStrategy; // wiring: pluggable authentication mechanism

    public AuthService(AuthStrategy authStrategy) {
        this.authStrategy = Objects.requireNonNull(authStrategy, "authStrategy must not be null");
    }

    /**
     * Authenticates the incoming request using the configured strategy.
     *
     * @param request the incoming HTTP request
     * @return the authentication result containing principal, roles, and success/failure
     */
    public AuthResult authenticate(HttpRequest request) {
        AuthResult result = authStrategy.authenticate(request);
        if (result.isAuthenticated()) {
            System.out.println("[AUTH] Authentication succeeded — principal='%s', roles=%s".formatted(
                    result.getPrincipal(), result.getRoles()));
        } else {
            System.out.println("[AUTH] Authentication failed — reason='%s'".formatted(
                    result.getErrorMessage()));
        }
        return result;
    }

    /**
     * Authorizes an authenticated principal against a route's required role.
     *
     * If the route has no "required-role" metadata, it is treated as a public route (always authorized).
     * Otherwise, the principal must hold the required role.
     *
     * @param authResult the result from a prior authenticate() call
     * @param route      the matched route to check authorization for
     * @return true if authorized, false otherwise
     */
    public boolean authorize(AuthResult authResult, Route route) {
        String requiredRole = route.getMetadata().get("required-role");

        // No required-role metadata → public route, always authorized
        if (requiredRole == null || requiredRole.isBlank()) {
            System.out.println("[AUTH] Authorization granted — route '%s' is public (no required-role)".formatted(
                    route.getId()));
            return true;
        }

        boolean hasRole = authResult.getRoles().contains(requiredRole);
        if (hasRole) {
            System.out.println("[AUTH] Authorization granted — principal='%s' has role '%s' for route '%s'".formatted(
                    authResult.getPrincipal(), requiredRole, route.getId()));
        } else {
            System.out.println("[AUTH] Authorization denied — principal='%s' lacks role '%s' for route '%s'".formatted(
                    authResult.getPrincipal(), requiredRole, route.getId()));
        }
        return hasRole;
    }

    /**
     * Swaps the authentication strategy at runtime (Strategy Pattern hot-swap).
     *
     * @param strategy the new authentication strategy
     */
    public void setStrategy(AuthStrategy strategy) {
        this.authStrategy = Objects.requireNonNull(strategy, "strategy must not be null");
        System.out.println("[AUTH] Strategy swapped to: %s".formatted(strategy.getStrategyName()));
    }
}
