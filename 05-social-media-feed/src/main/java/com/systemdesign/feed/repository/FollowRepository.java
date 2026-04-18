package com.systemdesign.feed.repository;

import com.systemdesign.feed.model.Follow;

import java.util.List;

public interface FollowRepository {

    void save(Follow follow);

    void delete(String followerId, String followeeId);

    /** Returns the IDs of users who follow this user. */
    List<String> getFollowerIds(String userId);

    /** Returns the IDs of users this user follows. */
    List<String> getFolloweeIds(String userId);

    boolean isFollowing(String followerId, String followeeId);

    int getFollowerCount(String userId);
}
