package com.systemdesign.feed.config;

import com.systemdesign.feed.controller.FeedController;
import com.systemdesign.feed.fanout.FanoutOnReadStrategy;
import com.systemdesign.feed.fanout.FanoutOnWriteStrategy;
import com.systemdesign.feed.fanout.FanoutService;
import com.systemdesign.feed.fanout.HybridFanoutStrategy;
import com.systemdesign.feed.model.User;
import com.systemdesign.feed.model.UserType;
import com.systemdesign.feed.ranking.EngagementRanker;
import com.systemdesign.feed.ranking.FeedRanker;
import com.systemdesign.feed.repository.*;
import com.systemdesign.feed.service.FeedService;
import com.systemdesign.feed.service.FollowService;
import com.systemdesign.feed.service.TweetService;
import com.systemdesign.feed.trending.TrendingService;

/**
 * Wires all components together. Acts as a simple DI container.
 */
public class AppConfig {

    private final UserRepository userRepo;
    private final TweetRepository tweetRepo;
    private final FollowRepository followRepo;
    private final TimelineCacheRepository timelineCache;
    private final TrendingRepository trendingRepo;

    private final FanoutService fanoutService;
    private final TrendingService trendingService;
    private final TweetService tweetService;
    private final FollowService followService;
    private final FeedService feedService;
    private final FeedController controller;

    public AppConfig() {
        // Repositories
        userRepo = new InMemoryUserRepository();
        tweetRepo = new InMemoryTweetRepository();
        followRepo = new InMemoryFollowRepository();
        timelineCache = new InMemoryTimelineCacheRepository();
        trendingRepo = new InMemoryTrendingRepository();

        // Fan-out strategy: Hybrid
        HybridFanoutStrategy hybridStrategy = new HybridFanoutStrategy(
                new FanoutOnWriteStrategy(),
                new FanoutOnReadStrategy(),
                User.CELEBRITY_THRESHOLD
        );

        // Services
        fanoutService = new FanoutService(hybridStrategy, followRepo, timelineCache, userRepo);
        trendingService = new TrendingService(trendingRepo);
        tweetService = new TweetService(tweetRepo, fanoutService, trendingService, userRepo);
        followService = new FollowService(followRepo, userRepo);

        // Ranker
        FeedRanker ranker = new EngagementRanker();
        feedService = new FeedService(timelineCache, tweetRepo, followRepo, userRepo, ranker);

        // Controller
        controller = new FeedController(tweetService, feedService, followService, trendingService);

        // Seed data
        seedUsers();
        seedFollows();
    }

    private void seedUsers() {
        userRepo.save(new User("alice", "alice", "Alice Johnson", "Software engineer", 350, 4, UserType.NORMAL));
        userRepo.save(new User("bob", "bob", "Bob Smith", "Coffee enthusiast", 200, 3, UserType.NORMAL));
        userRepo.save(new User("carol", "carol", "Carol Davis", "Music lover", 150, 3, UserType.NORMAL));
        userRepo.save(new User("elon", "elon", "Elon Musk", "CEO of everything", 50_000_000, 0, UserType.CELEBRITY));
        userRepo.save(new User("taylor", "taylor", "Taylor Swift", "Singer-songwriter", 30_000_000, 0, UserType.CELEBRITY));
        userRepo.save(new User("dave", "dave", "Dave Wilson", "Casual user", 500, 3, UserType.NORMAL));
    }

    private void seedFollows() {
        // alice follows bob, carol, elon, taylor
        followService.follow("alice", "bob");
        followService.follow("alice", "carol");
        followService.follow("alice", "elon");
        followService.follow("alice", "taylor");

        // bob follows alice, carol, elon
        followService.follow("bob", "alice");
        followService.follow("bob", "carol");
        followService.follow("bob", "elon");

        // carol follows alice, bob, taylor
        followService.follow("carol", "alice");
        followService.follow("carol", "bob");
        followService.follow("carol", "taylor");

        // dave follows alice, elon, taylor
        followService.follow("dave", "alice");
        followService.follow("dave", "elon");
        followService.follow("dave", "taylor");
    }

    public FeedController getController() { return controller; }
    public UserRepository getUserRepo() { return userRepo; }
    public FollowRepository getFollowRepo() { return followRepo; }
    public FollowService getFollowService() { return followService; }
}
