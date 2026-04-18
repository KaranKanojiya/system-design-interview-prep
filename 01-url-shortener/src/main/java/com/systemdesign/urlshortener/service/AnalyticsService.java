package com.systemdesign.urlshortener.service;

import com.systemdesign.urlshortener.model.ClickEvent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Simulates an analytics/event-tracking service.
 * In production, this would write to Kafka/Kinesis and query from a time-series DB or data warehouse.
 */
public class AnalyticsService {

    // Synchronized list to simulate a thread-safe event store
    private final List<ClickEvent> eventStore = Collections.synchronizedList(new ArrayList<>());

    /**
     * Record a click event for a short code.
     */
    public void recordClick(String shortCode, String ipAddress, String userAgent) {
        ClickEvent event = new ClickEvent(shortCode, LocalDateTime.now(), ipAddress, userAgent);
        eventStore.add(event);
    }

    /**
     * Get total click count for a short code.
     */
    public long getClickCount(String shortCode) {
        return eventStore.stream()
                .filter(e -> e.getShortCode().equals(shortCode))
                .count();
    }

    /**
     * Get the most recent click events for a short code.
     */
    public List<ClickEvent> getRecentClicks(String shortCode, int limit) {
        // Collect matching events, reverse for most-recent-first, then limit
        List<ClickEvent> matching = eventStore.stream()
                .filter(e -> e.getShortCode().equals(shortCode))
                .collect(Collectors.toList());

        Collections.reverse(matching);

        return matching.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }
}
