package com.systemdesign.observability.service;

// Wiring: TracingService manages distributed trace lifecycle.
// Dependencies injected via constructor:
//   traceRepo      — persists assembled Traces
//   assembler      — collects Spans and assembles them into Traces
//   samplingEngine — decides whether to sample a trace (Strategy pattern)

import com.systemdesign.observability.model.Span;
import com.systemdesign.observability.model.Trace;
import com.systemdesign.observability.model.TraceContext;
import com.systemdesign.observability.engine.TraceAssembler;
import com.systemdesign.observability.repository.TraceRepository;
import com.systemdesign.observability.strategy.sampling.SamplingStrategy;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TracingService — business logic for distributed tracing.
 *
 * FLOW — startTrace(operationName, serviceName):
 *   1. Create a new TraceContext (fresh traceId + spanId)
 *   2. Check sampling via SamplingStrategy — if not sampled, still create span but mark unsampled
 *   3. Build root Span (parentSpanId = null)
 *   4. Print [TRACING] log
 *   5. Return the root span
 *
 * FLOW — finishSpan(span):
 *   1. Call span.finish() to stamp end time
 *   2. Add span to TraceAssembler
 *   3. Attempt to assemble the full Trace
 *   4. If assembled, save to TraceRepository
 *   5. Print [TRACING] log
 */
public class TracingService {

    private final TraceRepository traceRepo;        // persists completed traces
    private final TraceAssembler assembler;          // buffers spans and assembles traces
    private final SamplingStrategy samplingEngine;   // decides whether to sample a trace

    // Tracks active trace contexts for child-span creation
    private final Map<String, TraceContext> activeContexts = new HashMap<>();

    public TracingService(TraceRepository traceRepo, TraceAssembler assembler,
                          SamplingStrategy samplingEngine) {
        this.traceRepo = traceRepo;
        this.assembler = assembler;
        this.samplingEngine = samplingEngine;
    }

    // ---- trace lifecycle ----

    /**
     * Starts a brand-new distributed trace and returns the root span.
     *
     * @param operationName the name of the entry-point operation
     * @param serviceName   the service that initiated the trace
     * @return the root Span of the new trace
     */
    public Span startTrace(String operationName, String serviceName) {
        // 1. Create fresh trace context
        TraceContext context = TraceContext.newTrace();
        String traceId = context.getTraceId();

        // 2. Sampling decision
        boolean sampled = samplingEngine.shouldSample(context, operationName);

        // 3. Build root span (no parent)
        Span rootSpan = new Span.Builder(traceId, operationName, serviceName)
                .build();

        // 4. Track the context for child-span creation
        activeContexts.put(traceId, context);

        System.out.println("[TRACING] Started trace " + traceId
                + " | operation='" + operationName + "' | service='" + serviceName
                + "' | sampled=" + sampled);

        return rootSpan;
    }

    /**
     * Creates a child span within an existing trace.
     *
     * @param traceId       the parent trace's ID
     * @param parentSpanId  the parent span's ID
     * @param operationName the name of this child operation
     * @param serviceName   the service executing this span
     * @return the child Span
     */
    public Span startSpan(String traceId, String parentSpanId,
                          String operationName, String serviceName) {
        Span childSpan = new Span.Builder(traceId, operationName, serviceName)
                .parentSpanId(parentSpanId)
                .build();

        System.out.println("[TRACING] Started span " + childSpan.getSpanId()
                + " | parent=" + parentSpanId
                + " | operation='" + operationName + "' | service='" + serviceName + "'");

        return childSpan;
    }

    /**
     * Finishes a span and attempts to assemble the complete trace.
     * If the trace assembler can build a full trace (root span present),
     * the trace is persisted to the repository.
     *
     * @param span the span to finish
     */
    public void finishSpan(Span span) {
        // 1. Stamp end time and compute duration
        span.finish();

        // 2. Add to assembler buffer
        assembler.addSpan(span);

        // 3. Attempt trace assembly
        Optional<Trace> assembled = assembler.assembleTrace(span.getTraceId());

        if (assembled.isPresent()) {
            // 4. Persist the completed trace
            traceRepo.save(assembled.get());
            activeContexts.remove(span.getTraceId());

            System.out.println("[TRACING] Assembled and saved trace " + span.getTraceId()
                    + " | spans=" + assembled.get().getSpanCount());
        } else {
            System.out.println("[TRACING] Finished span " + span.getSpanId()
                    + " | trace " + span.getTraceId() + " pending assembly");
        }
    }

    // ---- querying ----

    /**
     * Retrieves a completed trace by its ID.
     */
    public Optional<Trace> getTrace(String traceId) {
        return traceRepo.findById(traceId);
    }

    /**
     * Returns all traces that involved the given service.
     */
    public List<Trace> getTracesByService(String serviceName) {
        return traceRepo.findByServiceName(serviceName);
    }

    /**
     * Returns the most recent traces, sorted by start time descending.
     *
     * @param limit maximum number of traces to return
     */
    public List<Trace> getRecentTraces(int limit) {
        return traceRepo.findAll().stream()
                .sorted(Comparator.comparing(Trace::getStartTime).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
}
