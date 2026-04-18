package com.systemdesign.feed.repository;

import com.systemdesign.feed.model.Tweet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryTweetRepository implements TweetRepository {

    private final ConcurrentHashMap<String, Tweet> store = new ConcurrentHashMap<>();

    @Override
    public void save(Tweet tweet) {
        store.put(tweet.getTweetId(), tweet);
    }

    @Override
    public Optional<Tweet> findById(String tweetId) {
        return Optional.ofNullable(store.get(tweetId));
    }

    @Override
    public List<Tweet> findByUserId(String userId, int limit) {
        return store.values().stream()
                .filter(t -> t.getUserId().equals(userId) && !t.isDeleted())
                .sorted(Comparator.comparing(Tweet::getCreatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<Tweet> findByUserIds(List<String> userIds, int limit) {
        Set<String> userIdSet = new HashSet<>(userIds);
        return store.values().stream()
                .filter(t -> userIdSet.contains(t.getUserId()) && !t.isDeleted())
                .sorted(Comparator.comparing(Tweet::getCreatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<Tweet> findAll() {
        return new ArrayList<>(store.values());
    }
}
