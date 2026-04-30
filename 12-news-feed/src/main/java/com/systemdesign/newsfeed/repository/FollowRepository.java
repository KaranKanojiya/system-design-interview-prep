package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.Follow;

import java.util.List;
import java.util.Optional;

/**
 * FollowRepository — Data access interface for Follow (social graph edges).
 *
 * In production: this would be a graph database (Facebook's TAO) or a
 * wide-column store (Cassandra) with two lookup patterns:
 * - followers(userId) -> who follows this user?  (for fan-out on write)
 * - following(userId) -> who does this user follow?  (for fan-out on read)
 */
public interface FollowRepository {

    void save(Follow follow);

    void delete(String followerId, String followeeId);

    Optional<Follow> find(String followerId, String followeeId);

    /**
     * Get all users who follow the given userId.
     * Used by fan-out-on-write to push posts to followers.
     */
    List<String> findFollowerIds(String userId);

    /**
     * Get all users that the given userId follows.
     * Used by fan-out-on-read to pull posts from followed users.
     */
    List<String> findFollowingIds(String userId);

    int countFollowers(String userId);

    int countFollowing(String userId);

    boolean exists(String followerId, String followeeId);

    long count();
}
