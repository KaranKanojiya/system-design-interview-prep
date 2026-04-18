package com.systemdesign.feed.fanout;

import com.systemdesign.feed.model.Tweet;
import com.systemdesign.feed.model.User;
import com.systemdesign.feed.repository.TimelineCacheRepository;

import java.util.List;

/**
 * PULL model: When a celebrity posts, do NOT push to followers.
 * The tweet stays in the celebrity's own timeline store.
 * Followers pull it at read time when they open their feed.
 *
 * Pros: No expensive write amplification for celebrities.
 * Cons: Slower reads (must query celebrity timelines on the fly).
 */
public class FanoutOnReadStrategy implements FanoutStrategy {

    @Override
    public void fanout(Tweet tweet, User poster, List<String> followerIds,
                       TimelineCacheRepository timelineCache) {
        // Intentionally does NOTHING. No push.
        // The tweet is already saved in TweetRepository.
        // Followers will pull it when they request their feed.
        System.out.println("  [FANOUT-READ] Celebrity @" + poster.getUsername()
                + " (" + String.format("%,d", poster.getFollowerCount())
                + " followers) -- tweet stored, will be pulled at read time");
        System.out.println("  [FANOUT-READ] >>> " + String.format("%,d", poster.getFollowerCount())
                + " timeline writes AVOIDED by using fan-out-on-read <<<");
    }

    @Override
    public String name() {
        return "Fan-out on Read (Pull)";
    }
}
