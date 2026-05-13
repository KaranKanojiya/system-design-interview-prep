package com.systemdesign.messagequeue.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Metrics for a topic-partition — tracks throughput, byte rates, and consumer lag.
 * Used for monitoring dashboards and auto-scaling decisions.
 */
public class QueueMetrics {

    // --- counters ---
    private long messagesIn;       // total messages produced
    private long messagesOut;      // total messages consumed
    private long bytesIn;          // total bytes produced
    private long bytesOut;         // total bytes consumed

    // --- lag ---
    private long lag;              // unconsumed messages (messagesIn - messagesOut)

    // --- timing ---
    private Instant firstMessageTime;  // timestamp of the first recorded message (for rate calc)
    private Instant lastUpdateTime;    // last time metrics were updated

    public QueueMetrics() {
        this.messagesIn = 0;
        this.messagesOut = 0;
        this.bytesIn = 0;
        this.bytesOut = 0;
        this.lag = 0;
        this.firstMessageTime = null;
        this.lastUpdateTime = Instant.now();
    }

    // --- getters ---

    public long getMessagesIn() { return messagesIn; }
    public long getMessagesOut() { return messagesOut; }
    public long getBytesIn() { return bytesIn; }
    public long getBytesOut() { return bytesOut; }
    public long getLag() { return lag; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }

    // --- recording ---

    /** Records an inbound message (produced). Updates counters and lag. */
    public void recordIn(Message message) {
        if (firstMessageTime == null) {
            firstMessageTime = Instant.now();
        }
        messagesIn++;
        bytesIn += message.getSize();
        lag = messagesIn - messagesOut;
        lastUpdateTime = Instant.now();
    }

    /** Records an outbound message (consumed). Updates counters and lag. */
    public void recordOut(Message message) {
        messagesOut++;
        bytesOut += message.getSize();
        lag = messagesIn - messagesOut;
        lastUpdateTime = Instant.now();
    }

    /**
     * Returns the average produce rate (messages per second) since the first message.
     * Returns 0.0 if no messages have been recorded yet.
     */
    public double getProduceRate() {
        if (firstMessageTime == null || messagesIn == 0) {
            return 0.0;
        }
        long elapsedSeconds = Duration.between(firstMessageTime, Instant.now()).toSeconds();
        if (elapsedSeconds == 0) {
            return messagesIn; // all messages arrived in < 1 second
        }
        return (double) messagesIn / elapsedSeconds;
    }

    @Override
    public String toString() {
        return "QueueMetrics{in=" + messagesIn + ", out=" + messagesOut
                + ", lag=" + lag + ", bytesIn=" + bytesIn
                + ", bytesOut=" + bytesOut + "}";
    }
}
