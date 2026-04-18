package com.systemdesign.feed.repository;

import com.systemdesign.feed.model.Follow;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryFollowRepository implements FollowRepository {

    // userId -> set of follower IDs (who follows this user)
    private final ConcurrentHashMap<String, Set<String>> followers = new ConcurrentHashMap<>();
    // userId -> set of followee IDs (who this user follows)
    private final ConcurrentHashMap<String, Set<String>> followees = new ConcurrentHashMap<>();

    @Override
    public void save(Follow follow) {
        followers.computeIfAbsent(follow.getFolloweeId(), k -> ConcurrentHashMap.newKeySet())
                .add(follow.getFollowerId());
        followees.computeIfAbsent(follow.getFollowerId(), k -> ConcurrentHashMap.newKeySet())
                .add(follow.getFolloweeId());
    }

    @Override
    public void delete(String followerId, String followeeId) {
        Set<String> followerSet = followers.get(followeeId);
        if (followerSet != null) {
            followerSet.remove(followerId);
        }
        Set<String> followeeSet = followees.get(followerId);
        if (followeeSet != null) {
            followeeSet.remove(followeeId);
        }
    }

    @Override
    public List<String> getFollowerIds(String userId) {
        Set<String> set = followers.get(userId);
        return set != null ? new ArrayList<>(set) : Collections.emptyList();
    }

    @Override
    public List<String> getFolloweeIds(String userId) {
        Set<String> set = followees.get(userId);
        return set != null ? new ArrayList<>(set) : Collections.emptyList();
    }

    @Override
    public boolean isFollowing(String followerId, String followeeId) {
        Set<String> set = followees.get(followerId);
        return set != null && set.contains(followeeId);
    }

    @Override
    public int getFollowerCount(String userId) {
        Set<String> set = followers.get(userId);
        return set != null ? set.size() : 0;
    }
}
