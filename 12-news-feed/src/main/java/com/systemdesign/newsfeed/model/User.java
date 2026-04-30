package com.systemdesign.newsfeed.model;

import java.time.LocalDateTime;

/**
 * User — Core user entity for the news feed system.
 *
 * Design notes for interview:
 * - followerCount is denormalized (kept in sync by SocialGraphService).
 *   In production, this would be a counter in Redis/Cassandra to avoid
 *   counting millions of rows on every request.
 * - isCelebrity() threshold (10,000 followers) drives the hybrid fan-out
 *   decision: celebrities use fan-out-on-read, normal users use fan-out-on-write.
 *   Facebook/Instagram use similar thresholds (though theirs are higher).
 */
public class User {

    private final String userId;
    private final String name;
    private final String email;

    // --- Denormalized counters (kept in sync by SocialGraphService) ---
    // In production these would be atomic counters in a distributed store
    private int followerCount;
    private int followingCount;
    private int postCount;

    private final LocalDateTime joinedAt;

    // --- Celebrity threshold ---
    // This drives the hybrid fan-out strategy decision.
    // If a user has more than 10,000 followers, pushing to ALL follower
    // timelines on every post is prohibitively expensive (O(10K+) writes).
    // Instead, their posts are pulled at read time.
    private static final int CELEBRITY_THRESHOLD = 10_000;

    public User(String userId, String name, String email) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.followerCount = 0;
        this.followingCount = 0;
        this.postCount = 0;
        this.joinedAt = LocalDateTime.now();
    }

    // --- Celebrity detection ---
    // Called by HybridFanoutStrategy to decide fan-out path.
    // In production, this flag might be cached or stored as a user attribute
    // rather than computed every time.
    public boolean isCelebrity() {
        return followerCount > CELEBRITY_THRESHOLD;
    }

    // --- Getters ---

    public String getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public int getFollowerCount() {
        return followerCount;
    }

    public int getFollowingCount() {
        return followingCount;
    }

    public int getPostCount() {
        return postCount;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    // --- Setters for denormalized counters ---
    // Only SocialGraphService and PostService should call these.

    public void setFollowerCount(int followerCount) {
        this.followerCount = followerCount;
    }

    public void setFollowingCount(int followingCount) {
        this.followingCount = followingCount;
    }

    public void setPostCount(int postCount) {
        this.postCount = postCount;
    }

    public void incrementPostCount() {
        this.postCount++;
    }

    @Override
    public String toString() {
        return String.format("User{id='%s', name='%s', followers=%d, following=%d, posts=%d, celebrity=%s}",
                userId, name, followerCount, followingCount, postCount, isCelebrity());
    }
}
