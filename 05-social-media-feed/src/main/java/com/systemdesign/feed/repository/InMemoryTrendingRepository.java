package com.systemdesign.feed.repository;

import com.systemdesign.feed.model.TrendingTopic;

import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryTrendingRepository implements TrendingRepository {

    private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();

    @Override
    public void incrementHashtag(String hashtag) {
        counts.computeIfAbsent(hashtag.toLowerCase(), k -> new AtomicLong(0)).incrementAndGet();
    }

    @Override
    public long getCount(String hashtag) {
        AtomicLong count = counts.get(hashtag.toLowerCase());
        return count != null ? count.get() : 0;
    }

    @Override
    public List<TrendingTopic> getTopTrending(int limit) {
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, AtomicLong>>comparingLong(e -> e.getValue().get()).reversed())
                .limit(limit)
                .map(e -> {
                    long count = e.getValue().get();
                    // Score combines count with a simple weighting
                    double score = count * 1.0;
                    return new TrendingTopic(e.getKey(), count, score);
                })
                .collect(Collectors.toList());
    }

    @Override
    public void resetCounts() {
        counts.clear();
    }
}
