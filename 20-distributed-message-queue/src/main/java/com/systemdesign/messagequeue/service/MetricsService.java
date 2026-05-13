package com.systemdesign.messagequeue.service;

import com.systemdesign.messagequeue.model.Message;
import com.systemdesign.messagequeue.model.QueueMetrics;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Queue metrics tracking — records produce/consume events and provides dashboards.
 *
 * Flow (recordProduce):
 *  1. Get or create QueueMetrics for the topic
 *  2. Call recordIn() to update counters (messagesIn, bytesIn, lag)
 *
 * Flow (recordConsume):
 *  1. Get or create QueueMetrics for the topic
 *  2. Call recordOut() to update counters (messagesOut, bytesOut, lag)
 */
public class MetricsService {

    // --- state: topicName -> metrics ---
    private final Map<String, QueueMetrics> topicMetrics;

    public MetricsService() {
        this.topicMetrics = new ConcurrentHashMap<>();
    }

    // ===================== Recording =====================

    /**
     * Records a produce event for a topic.
     *
     * @param topic   the topic name
     * @param message the produced message
     */
    public void recordProduce(String topic, Message message) {
        QueueMetrics metrics = topicMetrics.computeIfAbsent(topic, k -> new QueueMetrics());
        metrics.recordIn(message);
    }

    /**
     * Records a consume event for a topic.
     *
     * @param topic   the topic name
     * @param message the consumed message
     */
    public void recordConsume(String topic, Message message) {
        QueueMetrics metrics = topicMetrics.computeIfAbsent(topic, k -> new QueueMetrics());
        metrics.recordOut(message);
    }

    // ===================== Queries =====================

    /**
     * Returns metrics for a specific topic.
     *
     * @param topic the topic name
     * @return the QueueMetrics if tracked
     */
    public Optional<QueueMetrics> getMetrics(String topic) {
        return Optional.ofNullable(topicMetrics.get(topic));
    }

    /**
     * Returns metrics for all tracked topics.
     *
     * @return unmodifiable map of topicName to QueueMetrics
     */
    public Map<String, QueueMetrics> getAllMetrics() {
        return Collections.unmodifiableMap(topicMetrics);
    }

    // ===================== Dashboard =====================

    /**
     * Prints a formatted metrics dashboard to stdout.
     * Shows throughput, byte rates, and consumer lag for each tracked topic.
     */
    public void printDashboard() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║               MESSAGE QUEUE METRICS DASHBOARD               ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        if (topicMetrics.isEmpty()) {
            System.out.println("║  No metrics recorded yet.                                  ║");
        } else {
            for (Map.Entry<String, QueueMetrics> entry : topicMetrics.entrySet()) {
                String topic = entry.getKey();
                QueueMetrics m = entry.getValue();

                System.out.printf("║  Topic: %-50s ║%n", topic);
                System.out.printf("║    Messages In:  %-41d ║%n", m.getMessagesIn());
                System.out.printf("║    Messages Out: %-41d ║%n", m.getMessagesOut());
                System.out.printf("║    Bytes In:     %-41d ║%n", m.getBytesIn());
                System.out.printf("║    Bytes Out:    %-41d ║%n", m.getBytesOut());
                System.out.printf("║    Lag:          %-41d ║%n", m.getLag());
                System.out.printf("║    Produce Rate: %-41.2f ║%n", m.getProduceRate());
                System.out.println("║                                                            ║");
            }
        }

        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }
}
