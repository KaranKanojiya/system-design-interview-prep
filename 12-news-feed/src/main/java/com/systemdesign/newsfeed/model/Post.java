package com.systemdesign.newsfeed.model;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Post — Core post entity with Builder pattern.
 *
 * Design notes for interview:
 * - Like/comment/share counts use AtomicInteger for thread safety.
 *   In production, these would be distributed counters (Redis INCR or
 *   Cassandra counter columns) to avoid contention.
 * - getEngagementScore() is a simple weighted sum used by the ranking
 *   algorithm. Comments are worth 2x likes (higher intent signal),
 *   shares are worth 3x (strongest engagement signal — user is
 *   amplifying content to their own audience).
 * - Builder pattern used because posts have many optional fields
 *   (mediaUrl, contentType defaults, etc.)
 */
public class Post {

    private final String postId;
    private final String authorId;
    private final String authorName;
    private final String content;
    private final ContentType contentType;
    private final String mediaUrl;           // nullable — only for IMAGE/VIDEO/LINK
    private final LocalDateTime createdAt;

    // --- Engagement counters (thread-safe) ---
    // AtomicInteger because multiple threads can like/comment simultaneously.
    // In production: distributed counters in Redis or Cassandra.
    private final AtomicInteger likeCount;
    private final AtomicInteger commentCount;
    private final AtomicInteger shareCount;

    private Post(Builder builder) {
        this.postId = builder.postId;
        this.authorId = builder.authorId;
        this.authorName = builder.authorName;
        this.content = builder.content;
        this.contentType = builder.contentType;
        this.mediaUrl = builder.mediaUrl;
        this.createdAt = builder.createdAt;
        this.likeCount = new AtomicInteger(builder.likeCount);
        this.commentCount = new AtomicInteger(builder.commentCount);
        this.shareCount = new AtomicInteger(builder.shareCount);
    }

    // --- Engagement mutations ---
    // Called by EngagementService. Thread-safe via AtomicInteger.

    public int incrementLikes() {
        return likeCount.incrementAndGet();
    }

    public int incrementComments() {
        return commentCount.incrementAndGet();
    }

    public int incrementShares() {
        return shareCount.incrementAndGet();
    }

    /**
     * Engagement score — weighted sum of interactions.
     *
     * Formula: likes * 1 + comments * 2 + shares * 3
     *
     * Why these weights?
     * - Likes are low-effort (single tap), so weight = 1.
     * - Comments require thought and typing, higher intent signal, weight = 2.
     * - Shares mean the user wants their OWN audience to see this content,
     *   which is the strongest endorsement signal, weight = 3.
     *
     * Used by AlgorithmicRankingStrategy in the engagementBoost factor.
     */
    public double getEngagementScore() {
        return likeCount.get() + commentCount.get() * 2.0 + shareCount.get() * 3.0;
    }

    // --- Getters ---

    public String getPostId() {
        return postId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getContent() {
        return content;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public int getLikeCount() {
        return likeCount.get();
    }

    public int getCommentCount() {
        return commentCount.get();
    }

    public int getShareCount() {
        return shareCount.get();
    }

    @Override
    public String toString() {
        return String.format("Post{id='%s', author='%s', type=%s, content='%.30s...', likes=%d, comments=%d, shares=%d, engagement=%.1f, created=%s}",
                postId, authorName, contentType,
                content.length() > 30 ? content.substring(0, 30) : content,
                likeCount.get(), commentCount.get(), shareCount.get(),
                getEngagementScore(), createdAt);
    }

    // ============================
    // Builder
    // ============================

    public static class Builder {
        private String postId;
        private String authorId;
        private String authorName;
        private String content;
        private ContentType contentType = ContentType.TEXT;
        private String mediaUrl;
        private LocalDateTime createdAt = LocalDateTime.now();
        private int likeCount = 0;
        private int commentCount = 0;
        private int shareCount = 0;

        public Builder postId(String postId) {
            this.postId = postId;
            return this;
        }

        public Builder authorId(String authorId) {
            this.authorId = authorId;
            return this;
        }

        public Builder authorName(String authorName) {
            this.authorName = authorName;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder contentType(ContentType contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder mediaUrl(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder likeCount(int likeCount) {
            this.likeCount = likeCount;
            return this;
        }

        public Builder commentCount(int commentCount) {
            this.commentCount = commentCount;
            return this;
        }

        public Builder shareCount(int shareCount) {
            this.shareCount = shareCount;
            return this;
        }

        public Post build() {
            if (postId == null || authorId == null || content == null) {
                throw new IllegalStateException("postId, authorId, and content are required");
            }
            return new Post(this);
        }
    }
}
