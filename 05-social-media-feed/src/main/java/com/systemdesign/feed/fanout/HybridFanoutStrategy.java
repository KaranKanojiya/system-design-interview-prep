package com.systemdesign.feed.fanout;

import com.systemdesign.feed.model.Tweet;
import com.systemdesign.feed.model.User;
import com.systemdesign.feed.repository.TimelineCacheRepository;

import java.util.List;

/**
 * HYBRID fan-out strategy -- what Twitter/X actually uses.
 *
 * The Celebrity Problem:
 * - A celebrity with 50M followers posts a tweet.
 * - Fan-out-on-write would require 50M writes to 50M timeline caches.
 * - This is extremely expensive and slow ("the thundering herd").
 *
 * The Solution:
 * - NORMAL users (< 10K followers): use fan-out-on-write (push model).
 *   Their tweets are immediately written to each follower's cache.
 *   Fast reads, manageable write cost.
 *
 * - CELEBRITY users (>= 10K followers): use fan-out-on-read (pull model).
 *   Their tweets are NOT pushed. Instead, when a follower opens their feed,
 *   the system pulls the celebrity's latest tweets on the fly and merges them.
 *
 * This hybrid approach gives the best of both worlds:
 * - Most users (normal) get instant feed updates via push.
 * - Celebrity tweets avoid write amplification.
 * - At read time, the feed merges pushed items + pulled celebrity tweets.
 */
public class HybridFanoutStrategy implements FanoutStrategy {

    private final FanoutOnWriteStrategy writeStrategy;
    private final FanoutOnReadStrategy readStrategy;
    private final int celebrityThreshold;

    public HybridFanoutStrategy(FanoutOnWriteStrategy writeStrategy,
                                 FanoutOnReadStrategy readStrategy,
                                 int celebrityThreshold) {
        this.writeStrategy = writeStrategy;
        this.readStrategy = readStrategy;
        this.celebrityThreshold = celebrityThreshold;
    }

    public HybridFanoutStrategy() {
        this(new FanoutOnWriteStrategy(), new FanoutOnReadStrategy(), User.CELEBRITY_THRESHOLD);
    }

    @Override
    public void fanout(Tweet tweet, User poster, List<String> followerIds,
                       TimelineCacheRepository timelineCache) {
        boolean isCeleb = poster.isCelebrity();
        String strategyName = isCeleb ? "READ (pull)" : "WRITE (push)";
        String typeLabel = isCeleb ? "CELEBRITY" : "NORMAL";

        System.out.println("  [HYBRID] @" + poster.getUsername() + " is " + typeLabel
                + " (" + String.format("%,d", poster.getFollowerCount())
                + " followers) -> using " + strategyName + " fan-out");

        if (isCeleb) {
            readStrategy.fanout(tweet, poster, followerIds, timelineCache);
        } else {
            writeStrategy.fanout(tweet, poster, followerIds, timelineCache);
        }
    }

    @Override
    public String name() {
        return "Hybrid Fan-out (Write for normal, Read for celebrity)";
    }

    public FanoutOnWriteStrategy getWriteStrategy() { return writeStrategy; }
    public FanoutOnReadStrategy getReadStrategy() { return readStrategy; }
    public int getCelebrityThreshold() { return celebrityThreshold; }
}
