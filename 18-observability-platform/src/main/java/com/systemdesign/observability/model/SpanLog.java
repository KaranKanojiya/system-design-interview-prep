package com.systemdesign.observability.model;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable log entry attached to a Span — captures an event at a point in time.
 */
public class SpanLog {

    private final Instant timestamp;
    private final String event;
    private final Map<String, String> fields;

    public SpanLog(Instant timestamp, String event, Map<String, String> fields) {
        this.timestamp = timestamp;
        this.event = event;
        this.fields = fields != null ? Map.copyOf(fields) : Map.of();
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getEvent() {
        return event;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    @Override
    public String toString() {
        return "SpanLog{event='" + event + "', timestamp=" + timestamp + "}";
    }
}
