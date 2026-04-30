package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.Follow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryFollowRepository — ConcurrentHashMap-backed social graph storage.
 *
 * Uses a composite key "followerId:followeeId" for O(1) lookup.
 * Also maintains reverse indexes for efficient follower/following queries.
 *
 * In production: Facebook's TAO (graph database) stores edges with
 * O(1) edge existence check and O(degree) adjacency list traversal.
 */
public class InMemoryFollowRepository implements FollowRepository {

    // Primary storage: key = "followerId:followeeId"
    private final Map<String, Follow> follows = new ConcurrentHashMap<>();

    // Reverse indexes for efficient queries
    // followers: userId -> set of followerIds (who follows me?)
    private final Map<String, Set<String>> followersIndex = new ConcurrentHashMap<>();
    // following: userId -> set of followeeIds (who do I follow?)
    private final Map<String, Set<String>> followingIndex = new ConcurrentHashMap<>();

    private String key(String followerId, String followeeId) {
        return followerId + ":" + followeeId;
    }

    @Override
    public void save(Follow follow) {
        String k = key(follow.getFollowerId(), follow.getFolloweeId());
        follows.put(k, follow);

        // Update reverse indexes
        followersIndex
                .computeIfAbsent(follow.getFolloweeId(), id -> ConcurrentHashMap.newKeySet())
                .add(follow.getFollowerId());
        followingIndex
                .computeIfAbsent(follow.getFollowerId(), id -> ConcurrentHashMap.newKeySet())
                .add(follow.getFolloweeId());
    }

    @Override
    public void delete(String followerId, String followeeId) {
        String k = key(followerId, followeeId);
        follows.remove(k);

        // Update reverse indexes
        Set<String> followers = followersIndex.get(followeeId);
        if (followers != null) followers.remove(followerId);
        Set<String> following = followingIndex.get(followerId);
        if (following != null) following.remove(followeeId);
    }

    @Override
    public Optional<Follow> find(String followerId, String followeeId) {
        return Optional.ofNullable(follows.get(key(followerId, followeeId)));
    }

    @Override
    public List<String> findFollowerIds(String userId) {
        Set<String> followers = followersIndex.get(userId);
        return followers == null ? new ArrayList<>() : new ArrayList<>(followers);
    }

    @Override
    public List<String> findFollowingIds(String userId) {
        Set<String> following = followingIndex.get(userId);
        return following == null ? new ArrayList<>() : new ArrayList<>(following);
    }

    @Override
    public int countFollowers(String userId) {
        Set<String> followers = followersIndex.get(userId);
        return followers == null ? 0 : followers.size();
    }

    @Override
    public int countFollowing(String userId) {
        Set<String> following = followingIndex.get(userId);
        return following == null ? 0 : following.size();
    }

    @Override
    public boolean exists(String followerId, String followeeId) {
        return follows.containsKey(key(followerId, followeeId));
    }

    @Override
    public long count() {
        return follows.size();
    }
}
