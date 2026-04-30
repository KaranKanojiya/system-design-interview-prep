package com.systemdesign.videostreaming.repository;

import com.systemdesign.videostreaming.model.Video;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of VideoRepository.
 * Simulates a database table for video metadata.
 *
 * In production: JPA/Hibernate with PostgreSQL, indexed on videoId, uploaderId, createdAt.
 * Caching layer (Redis) for hot video metadata (frequently accessed videos).
 */
public class InMemoryVideoRepository implements VideoRepository {

    private final Map<String, Video> videos = new ConcurrentHashMap<>();

    @Override
    public void save(Video video) {
        videos.put(video.getVideoId(), video);
    }

    @Override
    public Optional<Video> findById(String videoId) {
        return Optional.ofNullable(videos.get(videoId));
    }

    @Override
    public List<Video> findAll() {
        return new ArrayList<>(videos.values());
    }

    @Override
    public List<Video> findByUploaderId(String uploaderId) {
        return videos.values().stream()
                .filter(v -> v.getUploaderId().equals(uploaderId))
                .collect(Collectors.toList());
    }

    @Override
    public void delete(String videoId) {
        videos.remove(videoId);
    }
}
