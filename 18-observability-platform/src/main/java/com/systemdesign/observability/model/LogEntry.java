package com.systemdesign.observability.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Structured log entry with optional trace correlation.
 * Essential fields are set via the constructor; optional fields via setters.
 */
public class LogEntry {

    private final String id;
    private final Instant timestamp;
    private final LogLevel level;
    private final String message;
    private final String serviceName;
    private String traceId;              // nullable — set for correlated logs
    private String spanId;               // nullable — set for correlated logs
    private final Map<String, String> attributes;

    public LogEntry(LogLevel level, String message, String serviceName) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.level = level;
        this.message = message;
        this.serviceName = serviceName;
        this.attributes = new HashMap<>();
    }

    // ---- setters for optional fields ----

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public void addAttribute(String key, String value) {
        this.attributes.put(key, value);
    }

    // ---- getters ----

    public String getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public LogLevel getLevel() { return level; }
    public String getMessage() { return message; }
    public String getServiceName() { return serviceName; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public Map<String, String> getAttributes() { return Map.copyOf(attributes); }

    @Override
    public String toString() {
        return "LogEntry{" + timestamp + " [" + level + "] " + serviceName
                + ": " + message + (traceId != null ? " traceId=" + traceId : "") + "}";
    }
}
