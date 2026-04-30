package com.systemdesign.videostreaming.repository;

import com.systemdesign.videostreaming.model.WatchHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory implementation of WatchHistoryRepository.
 *
 * Uses CopyOnWriteArrayList for thread safety during concurrent writes.
 * In production: append-only writes to Kafka → consumed by analytics pipeline.
 */
public class InMemoryWatchHistoryRepository implements WatchHistoryRepository {

    private final List<WatchHistory> history = new CopyOnWriteArrayList<>();

    @Override
    public void save(WatchHistory entry) {
        history.add(entry);
    }

    @Override
    public List<WatchHistory> findByUserId(String userId) {
        return history.stream()
                .filter(h -> h.getUserId().equals(userId))
                .collect(Collectors.toList());
    }

    @Override
    public List<WatchHistory> findByVideoId(String videoId) {
        return history.stream()
                .filter(h -> h.getVideoId().equals(videoId))
                .collect(Collectors.toList());
    }

    @Override
    public List<WatchHistory> findAll() {
        return new ArrayList<>(history);
    }
}
