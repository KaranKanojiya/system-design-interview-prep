package com.systemdesign.feed.model;

import java.time.LocalDateTime;

public class FeedItem implements Comparable<FeedItem> {

    private final Tweet tweet;
    private double score;
    private final FeedSource source;
    private final LocalDateTime addedAt;

    public FeedItem(Tweet tweet, double score, FeedSource source) {
        this.tweet = tweet;
        this.score = score;
        this.source = source;
        this.addedAt = LocalDateTime.now();
    }

    public Tweet getTweet() { return tweet; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public FeedSource getSource() { return source; }
    public LocalDateTime getAddedAt() { return addedAt; }

    @Override
    public int compareTo(FeedItem other) {
        // Higher score first (descending)
        return Double.compare(other.score, this.score);
    }

    @Override
    public String toString() {
        String contentPreview = tweet.getContent().length() > 60
                ? tweet.getContent().substring(0, 60) + "..."
                : tweet.getContent();
        return String.format("[%s] @%s: %s (score: %.2f)",
                source.name(), tweet.getUserId(), contentPreview, score);
    }
}
