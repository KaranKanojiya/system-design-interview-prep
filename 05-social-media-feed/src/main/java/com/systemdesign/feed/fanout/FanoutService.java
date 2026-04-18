package com.systemdesign.feed.fanout;

import com.systemdesign.feed.exception.UserNotFoundException;
import com.systemdesign.feed.model.Tweet;
import com.systemdesign.feed.model.User;
import com.systemdesign.feed.repository.FollowRepository;
import com.systemdesign.feed.repository.TimelineCacheRepository;
import com.systemdesign.feed.repository.UserRepository;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the fan-out process when a tweet is posted.
 */
public class FanoutService {

    private final HybridFanoutStrategy strategy;
    private final FollowRepository followRepo;
    private final TimelineCacheRepository timelineCache;
    private final UserRepository userRepo;

    private final AtomicInteger totalFanouts = new AtomicInteger(0);
    private final AtomicInteger totalTweetsProcessed = new AtomicInteger(0);

    public FanoutService(HybridFanoutStrategy strategy,
                         FollowRepository followRepo,
                         TimelineCacheRepository timelineCache,
                         UserRepository userRepo) {
        this.strategy = strategy;
        this.followRepo = followRepo;
        this.timelineCache = timelineCache;
        this.userRepo = userRepo;
    }

    /**
     * Processes a new tweet through the fan-out pipeline.
     *
     * @param tweet the newly posted tweet
     * @return the number of followers the tweet was fanned out to
     */
    public int processTweet(Tweet tweet) {
        User poster = userRepo.findById(tweet.getUserId())
                .orElseThrow(() -> new UserNotFoundException(tweet.getUserId()));

        List<String> followerIds = followRepo.getFollowerIds(tweet.getUserId());

        strategy.fanout(tweet, poster, followerIds, timelineCache);

        totalTweetsProcessed.incrementAndGet();
        totalFanouts.addAndGet(followerIds.size());

        return followerIds.size();
    }

    public String getStats() {
        return "FanoutService stats: " + totalTweetsProcessed.get()
                + " tweets processed, " + totalFanouts.get() + " total fan-outs";
    }

    public HybridFanoutStrategy getStrategy() { return strategy; }
}
