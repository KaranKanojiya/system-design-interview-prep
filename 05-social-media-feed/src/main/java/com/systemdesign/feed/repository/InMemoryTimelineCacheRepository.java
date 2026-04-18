package com.systemdesign.feed.repository;

import com.systemdesign.feed.model.FeedItem;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryTimelineCacheRepository implements TimelineCacheRepository {

    private static final int MAX_TIMELINE_SIZE = 200;

    private final ConcurrentHashMap<String, List<FeedItem>> store = new ConcurrentHashMap<>();

    @Override
    public synchronized void addToTimeline(String userId, FeedItem item) {
        List<FeedItem> timeline = store.computeIfAbsent(userId, k -> new ArrayList<>());
        timeline.add(item);
        // Keep sorted by score descending
        timeline.sort(Comparator.comparingDouble(FeedItem::getScore).reversed());
        // Enforce max size
        if (timeline.size() > MAX_TIMELINE_SIZE) {
            store.put(userId, new ArrayList<>(timeline.subList(0, MAX_TIMELINE_SIZE)));
        }
    }

    @Override
    public List<FeedItem> getTimeline(String userId, int limit) {
        List<FeedItem> timeline = store.getOrDefault(userId, Collections.emptyList());
        return timeline.stream()
                .sorted(Comparator.comparingDouble(FeedItem::getScore).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public synchronized void removeFromTimeline(String userId, String tweetId) {
        List<FeedItem> timeline = store.get(userId);
        if (timeline != null) {
            timeline.removeIf(item -> item.getTweet().getTweetId().equals(tweetId));
        }
    }

    @Override
    public int getTimelineSize(String userId) {
        List<FeedItem> timeline = store.get(userId);
        return timeline != null ? timeline.size() : 0;
    }

    @Override
    public void clearTimeline(String userId) {
        store.remove(userId);
    }
}
