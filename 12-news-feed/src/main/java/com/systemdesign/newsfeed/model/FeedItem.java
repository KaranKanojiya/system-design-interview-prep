package com.systemdesign.newsfeed.model;

/**
 * FeedItem — A single item in a user's feed, wrapping a Post with ranking metadata.
 *
 * Design notes for interview:
 * - score: computed by the ranking strategy (algorithmic or chronological).
 * - source: tracks WHERE this item came from in the feed assembly pipeline.
 *   TIMELINE_CACHE = pre-computed via fan-out-on-write (fast read).
 *   PULLED_ON_READ = fetched at read time for celebrity posts (slow but avoids write amplification).
 *   MERGED = combined from both sources after deduplication.
 * - position: the final position in the feed after ranking + pagination.
 * - Implements Comparable: sorted by score descending (highest score = top of feed).
 */
public class FeedItem implements Comparable<FeedItem> {

    private final Post post;
    private double score;
    private final FeedItemSource source;
    private int position;

    public FeedItem(Post post, double score, FeedItemSource source) {
        this.post = post;
        this.score = score;
        this.source = source;
        this.position = 0;
    }

    // --- Comparable: sort by score DESC ---
    // Higher score = more relevant = appears first in feed.
    // If scores are equal, fall back to creation time (newer first).
    @Override
    public int compareTo(FeedItem other) {
        int scoreCompare = Double.compare(other.score, this.score); // DESC
        if (scoreCompare != 0) {
            return scoreCompare;
        }
        // Tie-breaker: newer posts first
        return other.post.getCreatedAt().compareTo(this.post.getCreatedAt());
    }

    // --- Getters and setters ---

    public Post getPost() {
        return post;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public FeedItemSource getSource() {
        return source;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    @Override
    public String toString() {
        return String.format("FeedItem{pos=%d, score=%.4f, source=%s, post=%s}",
                position, score, source, post);
    }

    /**
     * FeedItemSource — Tracks where a feed item came from in the assembly pipeline.
     *
     * This is important for debugging and monitoring:
     * - If most items are PULLED_ON_READ, the user follows too many celebrities
     *   and feed generation will be slow.
     * - If most items are TIMELINE_CACHE, fan-out-on-write is working well.
     */
    public enum FeedItemSource {
        TIMELINE_CACHE,     // Pre-pushed via fan-out-on-write
        PULLED_ON_READ,     // Fetched at read time (celebrity posts)
        MERGED              // Combined from multiple sources after dedup
    }
}
