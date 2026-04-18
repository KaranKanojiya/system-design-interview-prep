package com.systemdesign.feed.repository;

import com.systemdesign.feed.model.FeedItem;

import java.util.List;

public interface TimelineCacheRepository {

    void addToTimeline(String userId, FeedItem item);

    /** Returns the user's cached timeline, sorted by score descending. */
    List<FeedItem> getTimeline(String userId, int limit);

    void removeFromTimeline(String userId, String tweetId);

    int getTimelineSize(String userId);

    void clearTimeline(String userId);
}
