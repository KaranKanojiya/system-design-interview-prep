package com.systemdesign.gateway.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parsed authentication token (e.g., decoded JWT) carried through the gateway pipeline.
 *
 * Flow: Authorization header → token parser → AuthToken → auth filter validates
 */
public class AuthToken {

    private final String tokenId;               // unique token identifier (jti)
    private final String subject;               // user or client principal (sub)
    private final Set<String> roles;            // granted roles/authorities
    private final Instant issuedAt;             // token issue time (iat)
    private final Instant expiresAt;            // token expiration time (exp)
    private final Map<String, String> claims;   // additional custom claims

    public AuthToken(String tokenId, String subject, Set<String> roles,
                     Instant issuedAt, Instant expiresAt, Map<String, String> claims) {
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId must not be null");
        this.subject = Objects.requireNonNull(subject, "subject must not be null");
        this.roles = Collections.unmodifiableSet(roles);
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.claims = Collections.unmodifiableMap(claims);
    }

    // ── Getters ──

    public String getTokenId() { return tokenId; }
    public String getSubject() { return subject; }
    public Set<String> getRoles() { return roles; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Map<String, String> getClaims() { return claims; }

    // ── Methods ──

    /** Returns true if the token has expired based on the current time. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /** Returns true if the token contains the given role. */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    @Override
    public String toString() {
        return "AuthToken{tokenId='%s', subject='%s', roles=%s, expired=%s}".formatted(
                tokenId, subject, roles, isExpired());
    }
}
