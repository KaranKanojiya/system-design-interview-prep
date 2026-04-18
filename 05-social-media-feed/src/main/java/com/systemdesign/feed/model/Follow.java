package com.systemdesign.feed.model;

import java.time.LocalDateTime;

public class Follow {

    private final String followerId;
    private final String followeeId;
    private final LocalDateTime createdAt;

    public Follow(String followerId, String followeeId) {
        this.followerId = followerId;
        this.followeeId = followeeId;
        this.createdAt = LocalDateTime.now();
    }

    public String getFollowerId() { return followerId; }
    public String getFolloweeId() { return followeeId; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "Follow{" + followerId + " -> " + followeeId + "}";
    }
}
