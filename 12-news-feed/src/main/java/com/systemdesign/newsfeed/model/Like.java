package com.systemdesign.newsfeed.model;

import java.time.LocalDateTime;

/**
 * Like — Represents a like on a post.
 *
 * Design notes for interview:
 * - Likes are stored as individual records (not just a counter) because:
 *   1. We need to know WHO liked a post ("Alice and 3 others liked this").
 *   2. We need to prevent duplicate likes (one like per user per post).
 *   3. Likes feed into the affinity score for algorithmic ranking
 *      (if viewer frequently likes author's posts, boost author in viewer's feed).
 * - In production, likes would be stored in a key-value store (Redis SET)
 *   for fast membership checks (has user X liked post Y?).
 */
public class Like {

    private final String userId;
    private final String postId;
    private final LocalDateTime createdAt;

    public Like(String userId, String postId) {
        this.userId = userId;
        this.postId = postId;
        this.createdAt = LocalDateTime.now();
    }

    public String getUserId() {
        return userId;
    }

    public String getPostId() {
        return postId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return String.format("Like{user='%s', post='%s', at=%s}", userId, postId, createdAt);
    }
}
