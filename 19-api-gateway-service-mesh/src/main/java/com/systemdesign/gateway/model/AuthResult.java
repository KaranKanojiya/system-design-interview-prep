package com.systemdesign.gateway.model;

import java.util.Collections;
import java.util.Set;

/**
 * Result of authentication and authorization checks in the gateway filter chain.
 *
 * Flow: AuthToken → auth filter validates → AuthResult → gate/allow request
 */
public class AuthResult {

    private final boolean authenticated;    // whether the caller's identity was verified
    private final boolean authorized;       // whether the caller has permission for this route
    private final String principal;         // identified user/client (null if unauthenticated)
    private final Set<String> roles;        // roles associated with the principal
    private final String errorMessage;      // reason for failure (null on success)

    private AuthResult(boolean authenticated, boolean authorized,
                       String principal, Set<String> roles, String errorMessage) {
        this.authenticated = authenticated;
        this.authorized = authorized;
        this.principal = principal;
        this.roles = roles != null ? Collections.unmodifiableSet(roles) : Set.of();
        this.errorMessage = errorMessage;
    }

    // ── Getters ──

    public boolean isAuthenticated() { return authenticated; }
    public boolean isAuthorized() { return authorized; }
    public String getPrincipal() { return principal; }
    public Set<String> getRoles() { return roles; }
    public String getErrorMessage() { return errorMessage; }

    /** Convenience: returns true only if both authenticated and authorized. */
    public boolean isAllowed() {
        return authenticated && authorized;
    }

    // ── Static factories ──

    /** Fully authenticated and authorized. */
    public static AuthResult success(String principal, Set<String> roles) {
        return new AuthResult(true, true, principal, roles, null);
    }

    /** Authentication failed — 401 Unauthorized. */
    public static AuthResult unauthorized(String reason) {
        return new AuthResult(false, false, null, Set.of(), reason);
    }

    /** Authenticated but not authorized — 403 Forbidden. */
    public static AuthResult forbidden(String reason) {
        return new AuthResult(true, false, null, Set.of(), reason);
    }

    @Override
    public String toString() {
        return "AuthResult{authenticated=%s, authorized=%s, principal='%s', error='%s'}".formatted(
                authenticated, authorized, principal, errorMessage);
    }
}
