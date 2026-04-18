package com.systemdesign.feed.service;

import com.systemdesign.feed.exception.UserNotFoundException;
import com.systemdesign.feed.model.*;
import com.systemdesign.feed.ranking.FeedRanker;
import com.systemdesign.feed.repository.FollowRepository;
import com.systemdesign.feed.repository.TimelineCacheRepository;
import com.systemdesign.feed.repository.TweetRepository;
import com.systemdesign.feed.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * THE CORE READ PATH -- merges fan-out-on-write (pushed) items
 * with fan-out-on-read (pulled) celebrity tweets.
 */
public class FeedService {

    private static final int CELEBRITY_TWEETS_PER_USER = 3;

    private final TimelineCacheRepository timelineCache;
    private final TweetRepository tweetRepo;
    private final FollowRepository followRepo;
    private final UserRepository userRepo;
    private final FeedRanker ranker;

    public FeedService(TimelineCacheRepository timelineCache, TweetRepository tweetRepo,
                       FollowRepository followRepo, UserRepository userRepo, FeedRanker ranker) {
        this.timelineCache = timelineCache;
        this.tweetRepo = tweetRepo;
        this.followRepo = followRepo;
        this.userRepo = userRepo;
        this.ranker = ranker;
    }

    /**
     * Generates a user's feed by merging pre-computed (pushed) and pulled (celebrity) tweets.
     *
     * Steps:
     * 1. Get pre-computed timeline from cache (pushed by fan-out-on-write for normal users)
     * 2. Identify celebrity followees
     * 3. Pull latest tweets from each celebrity (fan-out-on-read)
     * 4. Merge pushed + pulled items
     * 5. Deduplicate by tweetId
     * 6. Filter deleted tweets
     * 7. Rank using the configured FeedRanker
     * 8. Return top N items
     */
    public List<FeedItem> generateFeed(String userId, int limit) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // Step 1: Get pre-computed items (pushed via fan-out-on-write)
        List<FeedItem> pushedItems = timelineCache.getTimeline(userId, 200);
        int pushCount = pushedItems.size();

        // Step 2: Find celebrity followees
        List<String> followeeIds = followRepo.getFolloweeIds(userId);
        List<String> celebrityFolloweeIds = followeeIds.stream()
                .map(id -> userRepo.findById(id).orElse(null))
                .filter(u -> u != null && u.isCelebrity())
                .map(User::getUserId)
                .collect(Collectors.toList());

        // Step 3: Pull latest tweets from each celebrity (fan-out-on-read)
        List<FeedItem> pulledItems = new ArrayList<>();
        for (String celebId : celebrityFolloweeIds) {
            List<Tweet> celebTweets = tweetRepo.findByUserId(celebId, CELEBRITY_TWEETS_PER_USER);
            for (Tweet tweet : celebTweets) {
                double freshnessScore = computeFreshnessScore(tweet.getCreatedAt());
                pulledItems.add(new FeedItem(tweet, freshnessScore, FeedSource.FANOUT_READ));
            }
        }
        int pullCount = pulledItems.size();

        // Step 4: Merge pushed + pulled
        List<FeedItem> merged = new ArrayList<>();
        merged.addAll(pushedItems);
        merged.addAll(pulledItems);

        // Step 5: Deduplicate by tweetId
        Set<String> seen = new HashSet<>();
        List<FeedItem> deduped = new ArrayList<>();
        for (FeedItem item : merged) {
            if (seen.add(item.getTweet().getTweetId())) {
                deduped.add(item);
            }
        }

        // Step 6: Filter deleted tweets
        List<FeedItem> active = deduped.stream()
                .filter(item -> !item.getTweet().isDeleted())
                .collect(Collectors.toList());

        // Step 7: Rank
        List<FeedItem> ranked = ranker.rank(active);

        // Step 8: Take top limit
        List<FeedItem> result = ranked.stream()
                .limit(limit)
                .collect(Collectors.toList());

        System.out.println("  [FEED] @" + user.getUsername() + ": "
                + pushCount + " pre-computed + " + pullCount + " from "
                + celebrityFolloweeIds.size() + " celebrities = "
                + deduped.size() + " total -> ranked (" + ranker.name() + ") -> top " + limit);

        return result;
    }

    /**
     * Returns a user's own tweets in chronological order (their profile timeline).
     */
    public List<FeedItem> getUserTimeline(String userId, int limit) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        List<Tweet> tweets = tweetRepo.findByUserId(userId, limit);
        return tweets.stream()
                .map(t -> new FeedItem(t, computeFreshnessScore(t.getCreatedAt()), FeedSource.MERGED))
                .collect(Collectors.toList());
    }

    private double computeFreshnessScore(LocalDateTime createdAt) {
        long secondsAgo = Duration.between(createdAt, LocalDateTime.now()).getSeconds();
        return 1000.0 / (1.0 + secondsAgo * 0.01);
    }
}
