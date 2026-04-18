package com.systemdesign.feed.fanout;

import com.systemdesign.feed.model.Tweet;
import com.systemdesign.feed.model.User;
import com.systemdesign.feed.repository.TimelineCacheRepository;

import java.util.List;

public interface FanoutStrategy {

    /**
     * Distributes a tweet to followers' timelines.
     *
     * @param tweet        the tweet to distribute
     * @param poster       the user who posted the tweet
     * @param followerIds  the IDs of the poster's followers
     * @param timelineCache the timeline cache to write to
     */
    void fanout(Tweet tweet, User poster, List<String> followerIds, TimelineCacheRepository timelineCache);

    String name();
}
