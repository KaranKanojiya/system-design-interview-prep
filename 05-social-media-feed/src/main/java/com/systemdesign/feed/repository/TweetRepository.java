package com.systemdesign.feed.repository;

import com.systemdesign.feed.model.Tweet;

import java.util.List;
import java.util.Optional;

public interface TweetRepository {

    void save(Tweet tweet);

    Optional<Tweet> findById(String tweetId);

    /** Returns a user's tweets, newest first, excluding deleted ones. */
    List<Tweet> findByUserId(String userId, int limit);

    /** Returns combined tweets from multiple users, newest first, excluding deleted. */
    List<Tweet> findByUserIds(List<String> userIds, int limit);

    List<Tweet> findAll();
}
