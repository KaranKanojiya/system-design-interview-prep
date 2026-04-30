package com.systemdesign.videostreaming.repository;

import com.systemdesign.videostreaming.model.WatchHistory;

import java.util.List;

/**
 * Repository interface for watch history entries.
 *
 * In production: this data goes to a time-series database or event store
 * (ClickHouse, TimescaleDB, or Kafka → S3 data lake).
 * Queries are analytical: "top 10 most-watched videos this week".
 */
public interface WatchHistoryRepository {

    void save(WatchHistory entry);

    List<WatchHistory> findByUserId(String userId);

    List<WatchHistory> findByVideoId(String videoId);

    List<WatchHistory> findAll();
}
