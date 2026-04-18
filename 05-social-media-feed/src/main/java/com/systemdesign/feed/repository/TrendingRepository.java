package com.systemdesign.feed.repository;

import com.systemdesign.feed.model.TrendingTopic;

import java.util.List;

public interface TrendingRepository {

    void incrementHashtag(String hashtag);

    long getCount(String hashtag);

    /** Returns the top trending hashtags, sorted by count descending. */
    List<TrendingTopic> getTopTrending(int limit);

    void resetCounts();
}
