package com.systemdesign.feed.service;

import com.systemdesign.feed.exception.TweetNotFoundException;
import com.systemdesign.feed.exception.UserNotFoundException;
import com.systemdesign.feed.fanout.FanoutService;
import com.systemdesign.feed.model.Tweet;
import com.systemdesign.feed.model.User;
import com.systemdesign.feed.repository.TweetRepository;
import com.systemdesign.feed.repository.UserRepository;
import com.systemdesign.feed.trending.TrendingService;

import java.util.List;
import java.util.UUID;

public class TweetService {

    private final TweetRepository tweetRepo;
    private final FanoutService fanoutService;
    private final TrendingService trendingService;
    private final UserRepository userRepo;

    public TweetService(TweetRepository tweetRepo, FanoutService fanoutService,
                        TrendingService trendingService, UserRepository userRepo) {
        this.tweetRepo = tweetRepo;
        this.fanoutService = fanoutService;
        this.trendingService = trendingService;
        this.userRepo = userRepo;
    }

    /**
     * Posts a new tweet: save, fan-out, record trending hashtags.
     */
    public Tweet postTweet(String userId, String content, List<String> mediaUrls) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        Tweet tweet = new Tweet.Builder()
                .tweetId(UUID.randomUUID().toString().substring(0, 8))
                .userId(userId)
                .content(content)
                .mediaUrls(mediaUrls)
                .build();

        // 1. Persist the tweet
        tweetRepo.save(tweet);

        // 2. Fan-out to followers
        int fanoutCount = fanoutService.processTweet(tweet);

        // 3. Record hashtags for trending
        trendingService.recordTweet(tweet);

        System.out.println("  [TWEET] @" + user.getUsername() + ": " + content);
        if (!tweet.getHashtags().isEmpty()) {
            System.out.println("  [TWEET] Hashtags: " + tweet.getHashtags());
        }

        return tweet;
    }

    /**
     * Soft-deletes a tweet.
     */
    public void deleteTweet(String tweetId) {
        Tweet tweet = tweetRepo.findById(tweetId)
                .orElseThrow(() -> new TweetNotFoundException(tweetId));
        tweet.softDelete();
        System.out.println("  [DELETE] Tweet '" + tweetId + "' soft-deleted");
    }

    /**
     * Like a tweet.
     */
    public void likeTweet(String tweetId, String userId) {
        Tweet tweet = tweetRepo.findById(tweetId)
                .orElseThrow(() -> new TweetNotFoundException(tweetId));
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        int newCount = tweet.like();
        System.out.println("  [LIKE] @" + user.getUsername() + " liked tweet (total likes: " + newCount + ")");
    }

    /**
     * Retweet a tweet.
     */
    public void retweetTweet(String tweetId, String userId) {
        Tweet tweet = tweetRepo.findById(tweetId)
                .orElseThrow(() -> new TweetNotFoundException(tweetId));
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        int newCount = tweet.retweet();
        System.out.println("  [RETWEET] @" + user.getUsername() + " retweeted (total retweets: " + newCount + ")");
    }

    public TweetRepository getTweetRepo() { return tweetRepo; }
}
