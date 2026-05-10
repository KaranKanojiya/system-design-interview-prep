package com.systemdesign.gateway.model;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Simulated HTTP request flowing through the API gateway.
 *
 * Flow: Client → Gateway receives HttpRequest → route matching → filter chain → upstream
 */
public class HttpRequest {

    private final String id;                    // unique request identifier
    private final HttpMethod method;            // HTTP verb
    private final String path;                  // request path, e.g. "/api/users/123"
    private final Map<String, String> headers;  // HTTP headers
    private final Map<String, String> queryParams; // query string parameters
    private final String body;                  // request body (nullable for GET/HEAD)
    private final String clientIp;              // originating client IP
    private final Instant timestamp;            // when the request was received

    // ── private constructor wired from Builder ──
    private HttpRequest(Builder builder) {
        this.id = builder.id;
        this.method = builder.method;
        this.path = builder.path;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.queryParams = Collections.unmodifiableMap(new HashMap<>(builder.queryParams));
        this.body = builder.body;
        this.clientIp = builder.clientIp;
        this.timestamp = builder.timestamp;
    }

    // ── Getters ──

    public String getId() { return id; }
    public HttpMethod getMethod() { return method; }
    public String getPath() { return path; }
    public Map<String, String> getHeaders() { return headers; }
    public Map<String, String> getQueryParams() { return queryParams; }
    public String getBody() { return body; }
    public String getClientIp() { return clientIp; }
    public Instant getTimestamp() { return timestamp; }

    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public String toString() {
        return "HttpRequest{id='%s', method=%s, path='%s', clientIp='%s'}".formatted(
                id, method, path, clientIp);
    }

    // ── Builder ──

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private final HttpMethod method;
        private final String path;
        private Map<String, String> headers = new HashMap<>();
        private Map<String, String> queryParams = new HashMap<>();
        private String body;
        private String clientIp = "127.0.0.1";
        private Instant timestamp = Instant.now();

        /** Primary constructor — method and path are required. */
        public Builder(HttpMethod method, String path) {
            this.method = Objects.requireNonNull(method, "method must not be null");
            this.path = Objects.requireNonNull(path, "path must not be null");
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder header(String key, String value) { this.headers.put(key, value); return this; }
        public Builder headers(Map<String, String> headers) { this.headers.putAll(headers); return this; }
        public Builder queryParam(String key, String value) { this.queryParams.put(key, value); return this; }
        public Builder queryParams(Map<String, String> params) { this.queryParams.putAll(params); return this; }
        public Builder body(String body) { this.body = body; return this; }
        public Builder clientIp(String clientIp) { this.clientIp = clientIp; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }

        public HttpRequest build() {
            return new HttpRequest(this);
        }
    }
}
