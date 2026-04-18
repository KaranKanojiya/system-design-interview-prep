package com.systemdesign.feed.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Tweet {

    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#(\\w+)");

    private final String tweetId;
    private final String userId;
    private final String content;
    private final List<String> mediaUrls;
    private final List<String> hashtags;
    private final AtomicInteger likeCount;
    private final AtomicInteger retweetCount;
    private final AtomicInteger replyCount;
    private final LocalDateTime createdAt;
    private volatile boolean deleted;

    private Tweet(Builder builder) {
        this.tweetId = builder.tweetId;
        this.userId = builder.userId;
        this.content = builder.content;
        this.mediaUrls = builder.mediaUrls != null
                ? Collections.unmodifiableList(new ArrayList<>(builder.mediaUrls))
                : Collections.emptyList();
        this.hashtags = Collections.unmodifiableList(extractHashtags(builder.content));
        this.likeCount = new AtomicInteger(0);
        this.retweetCount = new AtomicInteger(0);
        this.replyCount = new AtomicInteger(0);
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.deleted = false;
    }

    private static List<String> extractHashtags(String content) {
        List<String> tags = new ArrayList<>();
        if (content == null) return tags;
        Matcher matcher = HASHTAG_PATTERN.matcher(content);
        while (matcher.find()) {
            String tag = matcher.group(1).toLowerCase();
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }

    public int like() {
        return likeCount.incrementAndGet();
    }

    public int retweet() {
        return retweetCount.incrementAndGet();
    }

    public int reply() {
        return replyCount.incrementAndGet();
    }

    public void softDelete() {
        this.deleted = true;
    }

    public double getEngagementScore() {
        return likeCount.get() * 1.0
                + retweetCount.get() * 2.0
                + replyCount.get() * 1.5;
    }

    // Getters
    public String getTweetId() { return tweetId; }
    public String getUserId() { return userId; }
    public String getContent() { return content; }
    public List<String> getMediaUrls() { return mediaUrls; }
    public List<String> getHashtags() { return hashtags; }
    public int getLikeCount() { return likeCount.get(); }
    public int getRetweetCount() { return retweetCount.get(); }
    public int getReplyCount() { return replyCount.get(); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isDeleted() { return deleted; }

    @Override
    public String toString() {
        return "Tweet{id='" + tweetId + "', user='" + userId + "', content='"
                + (content.length() > 50 ? content.substring(0, 50) + "..." : content)
                + "', likes=" + likeCount.get()
                + ", retweets=" + retweetCount.get()
                + ", replies=" + replyCount.get()
                + ", hashtags=" + hashtags
                + ", deleted=" + deleted + "}";
    }

    // ---- Builder ----

    public static class Builder {
        private String tweetId;
        private String userId;
        private String content;
        private List<String> mediaUrls;
        private LocalDateTime createdAt;

        public Builder tweetId(String tweetId) {
            this.tweetId = tweetId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder mediaUrls(List<String> mediaUrls) {
            this.mediaUrls = mediaUrls;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Tweet build() {
            if (tweetId == null || tweetId.isBlank()) {
                throw new IllegalArgumentException("tweetId is required");
            }
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId is required");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content is required");
            }
            if (content.length() > 280) {
                throw new IllegalArgumentException("content exceeds 280 characters");
            }
            return new Tweet(this);
        }
    }
}
