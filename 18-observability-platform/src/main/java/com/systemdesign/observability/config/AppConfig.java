package com.systemdesign.observability.config;

// Factory Pattern + Composition Root — single wiring point for all dependencies.
// Every object is lazily created on first access; strategy setters invalidate dependents.

import com.systemdesign.observability.controller.ObservabilityController;
import com.systemdesign.observability.display.ObservabilityStatsDisplay;
import com.systemdesign.observability.engine.LogProcessor;
import com.systemdesign.observability.engine.MetricAggregator;
import com.systemdesign.observability.engine.SamplingEngine;
import com.systemdesign.observability.engine.TimeSeriesStore;
import com.systemdesign.observability.engine.TraceAssembler;
import com.systemdesign.observability.repository.InMemoryAlertRepository;
import com.systemdesign.observability.repository.InMemoryLogRepository;
import com.systemdesign.observability.repository.InMemoryMetricRepository;
import com.systemdesign.observability.repository.InMemoryTraceRepository;
import com.systemdesign.observability.service.AlertService;
import com.systemdesign.observability.service.DashboardService;
import com.systemdesign.observability.service.LogService;
import com.systemdesign.observability.service.MetricService;
import com.systemdesign.observability.service.ObservabilityService;
import com.systemdesign.observability.service.ServiceMapService;
import com.systemdesign.observability.service.TracingService;
import com.systemdesign.observability.strategy.aggregation.AggregationStrategy;
import com.systemdesign.observability.strategy.aggregation.PercentileAggregationStrategy;
import com.systemdesign.observability.strategy.alerting.AlertingStrategy;
import com.systemdesign.observability.strategy.alerting.ThresholdAlertingStrategy;
import com.systemdesign.observability.strategy.sampling.HeadBasedSamplingStrategy;
import com.systemdesign.observability.strategy.sampling.SamplingStrategy;

/**
 * Composition root that wires all dependencies for the observability platform.
 * Uses lazy initialization — each component is created on first access.
 * Strategy setters invalidate dependent objects so they are re-created with the new strategy.
 */
public class AppConfig {

    // ---- repositories ----
    private InMemoryMetricRepository metricRepository;
    private InMemoryTraceRepository traceRepository;
    private InMemoryLogRepository logRepository;
    private InMemoryAlertRepository alertRepository;

    // ---- engines ----
    private MetricAggregator metricAggregator;
    private TraceAssembler traceAssembler;
    private LogProcessor logProcessor;
    private TimeSeriesStore timeSeriesStore;
    private SamplingEngine samplingEngine;

    // ---- strategies (swappable) ----
    private SamplingStrategy samplingStrategy;
    private AggregationStrategy aggregationStrategy;
    private AlertingStrategy alertingStrategy;

    // ---- services ----
    private MetricService metricService;
    private TracingService tracingService;
    private LogService logService;
    private AlertService alertService;
    private DashboardService dashboardService;
    private ServiceMapService serviceMapService;
    private ObservabilityService observabilityService;

    // ---- controller ----
    private ObservabilityController controller;

    // ---- display ----
    private ObservabilityStatsDisplay statsDisplay;

    // ========================================================================
    // Repository getters
    // ========================================================================

    public InMemoryMetricRepository getMetricRepository() {
        if (metricRepository == null) {
            metricRepository = new InMemoryMetricRepository();
        }
        return metricRepository;
    }

    public InMemoryTraceRepository getTraceRepository() {
        if (traceRepository == null) {
            traceRepository = new InMemoryTraceRepository();
        }
        return traceRepository;
    }

    public InMemoryLogRepository getLogRepository() {
        if (logRepository == null) {
            logRepository = new InMemoryLogRepository();
        }
        return logRepository;
    }

    public InMemoryAlertRepository getAlertRepository() {
        if (alertRepository == null) {
            alertRepository = new InMemoryAlertRepository();
        }
        return alertRepository;
    }

    // ========================================================================
    // Engine getters
    // ========================================================================

    public MetricAggregator getMetricAggregator() {
        if (metricAggregator == null) {
            metricAggregator = new MetricAggregator();
        }
        return metricAggregator;
    }

    public TraceAssembler getTraceAssembler() {
        if (traceAssembler == null) {
            traceAssembler = new TraceAssembler();
        }
        return traceAssembler;
    }

    public LogProcessor getLogProcessor() {
        if (logProcessor == null) {
            logProcessor = new LogProcessor();
        }
        return logProcessor;
    }

    public TimeSeriesStore getTimeSeriesStore() {
        if (timeSeriesStore == null) {
            timeSeriesStore = new TimeSeriesStore();
        }
        return timeSeriesStore;
    }

    public SamplingEngine getSamplingEngine() {
        if (samplingEngine == null) {
            samplingEngine = new SamplingEngine(getSamplingStrategy());
        }
        return samplingEngine;
    }

    // ========================================================================
    // Strategy getters and setters
    // ========================================================================

    public SamplingStrategy getSamplingStrategy() {
        if (samplingStrategy == null) {
            samplingStrategy = new HeadBasedSamplingStrategy(1.0);
        }
        return samplingStrategy;
    }

    /** Swaps the sampling strategy and clears all dependents that use it. */
    public void setSamplingStrategy(SamplingStrategy samplingStrategy) {
        this.samplingStrategy = samplingStrategy;
        this.samplingEngine = null;
        this.tracingService = null;
        this.observabilityService = null;
        this.controller = null;
    }

    public AggregationStrategy getAggregationStrategy() {
        if (aggregationStrategy == null) {
            aggregationStrategy = new PercentileAggregationStrategy(99);
        }
        return aggregationStrategy;
    }

    /** Swaps the aggregation strategy and clears all dependents that use it. */
    public void setAggregationStrategy(AggregationStrategy aggregationStrategy) {
        this.aggregationStrategy = aggregationStrategy;
        this.metricService = null;
        this.dashboardService = null;
        this.observabilityService = null;
        this.controller = null;
    }

    public AlertingStrategy getAlertingStrategy() {
        if (alertingStrategy == null) {
            alertingStrategy = new ThresholdAlertingStrategy();
        }
        return alertingStrategy;
    }

    /** Swaps the alerting strategy and clears all dependents that use it. */
    public void setAlertingStrategy(AlertingStrategy alertingStrategy) {
        this.alertingStrategy = alertingStrategy;
        this.alertService = null;
        this.observabilityService = null;
        this.controller = null;
    }

    // ========================================================================
    // Service getters
    // ========================================================================

    public MetricService getMetricService() {
        if (metricService == null) {
            metricService = new MetricService(
                    getMetricRepository(), getTimeSeriesStore(),
                    getMetricAggregator());
        }
        return metricService;
    }

    public TracingService getTracingService() {
        if (tracingService == null) {
            tracingService = new TracingService(
                    getTraceRepository(), getTraceAssembler(), getSamplingStrategy());
        }
        return tracingService;
    }

    public LogService getLogService() {
        if (logService == null) {
            logService = new LogService(getLogRepository(), getLogProcessor());
        }
        return logService;
    }

    public AlertService getAlertService() {
        if (alertService == null) {
            alertService = new AlertService(
                    getAlertRepository(), getAlertingStrategy(), getMetricService());
        }
        return alertService;
    }

    public DashboardService getDashboardService() {
        if (dashboardService == null) {
            dashboardService = new DashboardService(
                    getMetricService(), getTracingService(),
                    getLogService());
        }
        return dashboardService;
    }

    public ServiceMapService getServiceMapService() {
        if (serviceMapService == null) {
            serviceMapService = new ServiceMapService();
        }
        return serviceMapService;
    }

    public ObservabilityService getObservabilityService() {
        if (observabilityService == null) {
            observabilityService = new ObservabilityService(
                    getMetricService(), getTracingService(),
                    getLogService(), getAlertService(),
                    getDashboardService(), getServiceMapService());
        }
        return observabilityService;
    }

    // ========================================================================
    // Controller getter
    // ========================================================================

    public ObservabilityController getController() {
        if (controller == null) {
            controller = new ObservabilityController(
                    getObservabilityService(), getAlertService(), getDashboardService());
        }
        return controller;
    }

    // ========================================================================
    // Display getter
    // ========================================================================

    public ObservabilityStatsDisplay getStatsDisplay() {
        if (statsDisplay == null) {
            statsDisplay = new ObservabilityStatsDisplay(
                    getMetricService(), getTracingService(),
                    getLogService(), getAlertService(), getServiceMapService());
        }
        return statsDisplay;
    }
}
