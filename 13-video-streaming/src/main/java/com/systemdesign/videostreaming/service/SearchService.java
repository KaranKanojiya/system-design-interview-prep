package com.systemdesign.videostreaming.service;

import com.systemdesign.videostreaming.model.Video;
import com.systemdesign.videostreaming.model.VideoMetadata;
import com.systemdesign.videostreaming.model.VideoStatus;
import com.systemdesign.videostreaming.repository.VideoRepository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Simple video search service with substring matching.
 *
 * Search approaches (from simplest to most sophisticated):
 *   1. Substring matching (this implementation) — O(N) scan, no index
 *   2. Inverted index (Lucene/Elasticsearch) — O(1) lookup per term
 *   3. Semantic search (vector embeddings) — finds conceptually similar content
 *
 * In production: Elasticsearch cluster with:
 *   - Title/description: full-text search with analyzers (stemming, synonyms)
 *   - Tags: keyword match (exact terms)
 *   - Category: faceted search (filter, not score)
 *   - Autocomplete: edge-ngram tokenizer
 *   - Relevance tuning: BM25 + recency boost + popularity boost
 */
public class SearchService {

    private final VideoRepository videoRepository;
    private final Map<String, VideoMetadata> metadataMap;

    public SearchService(VideoRepository videoRepository, Map<String, VideoMetadata> metadataMap) {
        this.videoRepository = videoRepository;
        this.metadataMap = metadataMap;
    }

    /**
     * Search videos by title (case-insensitive substring match).
     * Only returns READY videos (not uploading/transcoding/deleted).
     */
    public List<Video> searchByTitle(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        String lowerQuery = query.toLowerCase();
        return videoRepository.findAll().stream()
                .filter(v -> v.getStatus() == VideoStatus.READY)
                .filter(v -> v.getTitle().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
    }

    /**
     * Search videos by tag (exact match, case-insensitive).
     */
    public List<Video> searchByTag(String tag) {
        if (tag == null || tag.isBlank()) return Collections.emptyList();

        String lowerTag = tag.toLowerCase();
        return videoRepository.findAll().stream()
                .filter(v -> v.getStatus() == VideoStatus.READY)
                .filter(v -> {
                    VideoMetadata meta = metadataMap.get(v.getVideoId());
                    if (meta == null) return false;
                    return meta.getTags().stream()
                            .anyMatch(t -> t.toLowerCase().equals(lowerTag));
                })
                .collect(Collectors.toList());
    }

    /**
     * Search videos by category (exact match, case-insensitive).
     */
    public List<Video> searchByCategory(String category) {
        if (category == null || category.isBlank()) return Collections.emptyList();

        String lowerCategory = category.toLowerCase();
        return videoRepository.findAll().stream()
                .filter(v -> v.getStatus() == VideoStatus.READY)
                .filter(v -> {
                    VideoMetadata meta = metadataMap.get(v.getVideoId());
                    if (meta == null) return false;
                    return meta.getCategory().toLowerCase().equals(lowerCategory);
                })
                .collect(Collectors.toList());
    }
}
