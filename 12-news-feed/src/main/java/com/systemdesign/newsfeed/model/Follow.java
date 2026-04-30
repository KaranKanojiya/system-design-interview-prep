package com.systemdesign.newsfeed.model;

import java.time.LocalDateTime;

/**
 * Follow — Represents a follow relationship between two users.
 *
 * Design notes for interview:
 * - This is an edge in the social graph (directed: A follows B != B follows A).
 * - In production, the social graph would be stored in a graph database
 *   (like TAO at Facebook) or a wide-column store (Cassandra) with two tables:
 *   - followers(userId) -> list of followerIds   (who follows me?)
 *   - following(userId) -> list of followeeIds   (who do I follow?)
 * - Both directions are needed:
 *   - followers: used during fan-out-on-write (push post to all followers)
 *   - following: used during fan-out-on-read (pull posts from followed users)
 */
public class Follow {

    private final String followerId;
    private final String followeeId;
    private final LocalDateTime createdAt;

    public Follow(String followerId, String followeeId) {
        this.followerId = followerId;
        this.followeeId = followeeId;
        this.createdAt = LocalDateTime.now();
    }

    public String getFollowerId() {
        return followerId;
    }

    public String getFolloweeId() {
        return followeeId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return String.format("Follow{follower='%s', followee='%s', at=%s}",
                followerId, followeeId, createdAt);
    }
}
