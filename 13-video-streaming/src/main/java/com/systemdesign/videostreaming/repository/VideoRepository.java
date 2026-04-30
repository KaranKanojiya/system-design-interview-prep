package com.systemdesign.videostreaming.repository;

import com.systemdesign.videostreaming.model.Video;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Video metadata (not binary data — that's VideoStore).
 *
 * In production: backed by PostgreSQL or DynamoDB.
 * The separation from VideoStore is crucial:
 *   - Metadata queries (search, filter, sort) need a database with indexes
 *   - Binary chunk data needs cheap, scalable object storage (S3)
 */
public interface VideoRepository {

    void save(Video video);

    Optional<Video> findById(String videoId);

    List<Video> findAll();

    List<Video> findByUploaderId(String uploaderId);

    void delete(String videoId);
}
