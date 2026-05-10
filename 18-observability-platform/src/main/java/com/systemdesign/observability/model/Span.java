package com.systemdesign.observability.model;

// Wiring: Span represents one unit of work in a distributed trace.
// Created by TracingService -> collected into Trace -> stored in TraceRepository.

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * A single span in a distributed trace, representing one operation within one service.
 * Constructed via Builder; call {@link #finish()} to stamp the end time.
 */
public class Span {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;       // nullable — root spans have no parent
    private final String operationName;
    private final String serviceName;
    private final Instant startTime;
    private Instant endTime;                  // set when finish() is called
    private Duration duration;                // computed from start/end
    private SpanStatus status;
    private final Map<String, String> tags;
    private final List<SpanLog> logs;

    private Span(Builder builder) {
        this.traceId = builder.traceId;
        this.spanId = builder.spanId;
        this.parentSpanId = builder.parentSpanId;
        this.operationName = builder.operationName;
        this.serviceName = builder.serviceName;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.duration = builder.duration;
        this.status = builder.status;
        this.tags = builder.tags != null ? new HashMap<>(builder.tags) : new HashMap<>();
        this.logs = builder.logs != null ? new ArrayList<>(builder.logs) : new ArrayList<>();
    }

    // ---- lifecycle ----

    /** Marks the span as finished — sets endTime to now and computes duration. */
    public void finish() {
        this.endTime = Instant.now();
        this.duration = Duration.between(startTime, endTime);
        if (this.status == null) {
            this.status = SpanStatus.OK;
        }
    }

    // ---- mutators ----

    public void setStatus(SpanStatus status) {
        this.status = status;
    }

    public void addLog(SpanLog log) {
        this.logs.add(log);
    }

    public void addTag(String key, String value) {
        this.tags.put(key, value);
    }

    // ---- getters ----

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getOperationName() { return operationName; }
    public String getServiceName() { return serviceName; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public Duration getDuration() { return duration; }
    public SpanStatus getStatus() { return status; }
    public Map<String, String> getTags() { return Collections.unmodifiableMap(tags); }
    public List<SpanLog> getLogs() { return Collections.unmodifiableList(logs); }

    @Override
    public String toString() {
        return "Span{traceId='" + traceId + "', spanId='" + spanId
                + "', op='" + operationName + "', service='" + serviceName
                + "', status=" + status + "}";
    }

    // ---- Builder ----

    public static class Builder {
        private String traceId;
        private String spanId = UUID.randomUUID().toString();
        private String parentSpanId;
        private String operationName;
        private String serviceName;
        private Instant startTime = Instant.now();
        private Instant endTime;
        private Duration duration;
        private SpanStatus status;
        private Map<String, String> tags;
        private List<SpanLog> logs;

        public Builder(String traceId, String operationName, String serviceName) {
            this.traceId = traceId;
            this.operationName = operationName;
            this.serviceName = serviceName;
        }

        public Builder spanId(String spanId) { this.spanId = spanId; return this; }
        public Builder parentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; return this; }
        public Builder startTime(Instant startTime) { this.startTime = startTime; return this; }
        public Builder endTime(Instant endTime) { this.endTime = endTime; return this; }
        public Builder duration(Duration duration) { this.duration = duration; return this; }
        public Builder status(SpanStatus status) { this.status = status; return this; }
        public Builder tags(Map<String, String> tags) { this.tags = tags; return this; }
        public Builder logs(List<SpanLog> logs) { this.logs = logs; return this; }

        public Span build() {
            return new Span(this);
        }
    }
}
