package com.systemdesign.newsfeed.config;

import com.systemdesign.newsfeed.controller.FeedController;
import com.systemdesign.newsfeed.display.FeedStatsDisplay;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.EngagementRepository;
import com.systemdesign.newsfeed.repository.FollowRepository;
import com.systemdesign.newsfeed.repository.InMemoryEngagementRepository;
import com.systemdesign.newsfeed.repository.InMemoryFollowRepository;
import com.systemdesign.newsfeed.repository.InMemoryPostRepository;
import com.systemdesign.newsfeed.repository.InMemoryUserRepository;
import com.systemdesign.newsfeed.repository.PostRepository;
import com.systemdesign.newsfeed.repository.UserRepository;
import com.systemdesign.newsfeed.service.EngagementService;
import com.systemdesign.newsfeed.service.FanoutService;
import com.systemdesign.newsfeed.service.FeedService;
import com.systemdesign.newsfeed.service.NotificationService;
import com.systemdesign.newsfeed.service.PostService;
import com.systemdesign.newsfeed.service.RankingService;
import com.systemdesign.newsfeed.service.SocialGraphService;
import com.systemdesign.newsfeed.service.TimelineService;
import com.systemdesign.newsfeed.store.InMemoryTimelineCache;
import com.systemdesign.newsfeed.store.TimelineCache;
import com.systemdesign.newsfeed.strategy.fanout.FanoutOnReadStrategy;
import com.systemdesign.newsfeed.strategy.fanout.FanoutOnWriteStrategy;
import com.systemdesign.newsfeed.strategy.fanout.HybridFanoutStrategy;
import com.systemdesign.newsfeed.strategy.ranking.AlgorithmicRankingStrategy;
import com.systemdesign.newsfeed.strategy.ranking.ChronologicalRankingStrategy;

/**
 * AppConfig — FACTORY: the ONLY place where "new ConcreteClass()" appears.
 *
 * Design notes for interview:
 * - This is the Composition Root / Dependency Injection container.
 * - All concrete implementations are wired here. No service knows about
 *   concrete implementations of its dependencies — only interfaces.
 * - In production, this would be replaced by Spring's @Configuration or
 *   Guice modules. The principle is the same: centralize object creation.
 *
 * DEPENDENCY GRAPH (bottom-up wiring order):
 *
 *   Layer 0 — Storage (no dependencies)
 *   ├── InMemoryUserRepository
 *   ├── InMemoryPostRepository
 *   ├── InMemoryFollowRepository
 *   ├── InMemoryEngagementRepository
 *   └── InMemoryTimelineCache
 *
 *   Layer 1 — Strategies (no service dependencies)
 *   ├── FanoutOnWriteStrategy
 *   ├── FanoutOnReadStrategy
 *   ├── HybridFanoutStrategy (wraps write + read)
 *   ├── AlgorithmicRankingStrategy
 *   └── ChronologicalRankingStrategy
 *
 *   Layer 2 — Core Services
 *   ├── SocialGraphService (depends on: FollowRepo, UserRepo)
 *   ├── NotificationService (no dependencies)
 *   ├── TimelineService (depends on: TimelineCache, PostRepo)
 *   └── RankingService (depends on: RankingStrategy)
 *
 *   Layer 3 — Orchestration Services
 *   ├── FanoutService (depends on: FanoutStrategy, SocialGraphService, TimelineCache, NotificationService)
 *   ├── PostService (depends on: PostRepo, UserRepo, FanoutService)
 *   ├── EngagementService (depends on: EngagementRepo, PostRepo, UserRepo, AlgorithmicRanking, NotificationService)
 *   └── FeedService (depends on: TimelineService, SocialGraphService, RankingService, PostRepo, UserRepo)
 *
 *   Layer 4 — Controller
 *   └── FeedController (depends on: PostService, FeedService, EngagementService, SocialGraphService)
 *
 *   Layer 5 — Display
 *   └── FeedStatsDisplay (depends on: repos + services for stats)
 */
public class AppConfig {

    // --- Repositories ---
    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final FollowRepository followRepository;
    private final EngagementRepository engagementRepository;

    // --- Store ---
    private final TimelineCache timelineCache;

    // --- Strategies ---
    private final FanoutOnWriteStrategy fanoutOnWriteStrategy;
    private final FanoutOnReadStrategy fanoutOnReadStrategy;
    private final HybridFanoutStrategy hybridFanoutStrategy;
    private final AlgorithmicRankingStrategy algorithmicRankingStrategy;
    private final ChronologicalRankingStrategy chronologicalRankingStrategy;

    // --- Services ---
    private final SocialGraphService socialGraphService;
    private final NotificationService notificationService;
    private final TimelineService timelineService;
    private final RankingService rankingService;
    private final FanoutService fanoutService;
    private final PostService postService;
    private final EngagementService engagementService;
    private final FeedService feedService;

    // --- Controller ---
    private final FeedController feedController;

    // --- Display ---
    private final FeedStatsDisplay feedStatsDisplay;

    public AppConfig() {
        // ==============================
        // Layer 0: Storage
        // ==============================
        this.userRepository = new InMemoryUserRepository();
        this.postRepository = new InMemoryPostRepository();
        this.followRepository = new InMemoryFollowRepository();
        this.engagementRepository = new InMemoryEngagementRepository();
        this.timelineCache = new InMemoryTimelineCache();

        // ==============================
        // Layer 1: Strategies
        // ==============================
        this.fanoutOnWriteStrategy = new FanoutOnWriteStrategy();
        this.fanoutOnReadStrategy = new FanoutOnReadStrategy();
        this.hybridFanoutStrategy = new HybridFanoutStrategy(fanoutOnWriteStrategy, fanoutOnReadStrategy);
        this.algorithmicRankingStrategy = new AlgorithmicRankingStrategy();
        this.chronologicalRankingStrategy = new ChronologicalRankingStrategy();

        // ==============================
        // Layer 2: Core Services
        // ==============================
        this.socialGraphService = new SocialGraphService(followRepository, userRepository);
        this.notificationService = new NotificationService();
        this.timelineService = new TimelineService(timelineCache, postRepository);
        this.rankingService = new RankingService(algorithmicRankingStrategy);

        // ==============================
        // Layer 3: Orchestration Services
        // ==============================
        this.fanoutService = new FanoutService(hybridFanoutStrategy, socialGraphService, timelineCache, notificationService);
        this.postService = new PostService(postRepository, userRepository, fanoutService);
        this.engagementService = new EngagementService(engagementRepository, postRepository, userRepository, algorithmicRankingStrategy, notificationService);
        this.feedService = new FeedService(timelineService, socialGraphService, rankingService, postRepository, userRepository);

        // ==============================
        // Layer 4: Controller
        // ==============================
        this.feedController = new FeedController(postService, feedService, engagementService, socialGraphService);

        // ==============================
        // Layer 5: Display
        // ==============================
        this.feedStatsDisplay = new FeedStatsDisplay(userRepository, postService, socialGraphService, fanoutService, feedService, engagementRepository);

        // ==============================
        // Seed data
        // ==============================
        seedUsers();
    }

    /**
     * Seed users — mix of normal users and celebrities.
     * Celebrity = followerCount > 10,000 (set artificially for demo).
     */
    private void seedUsers() {
        // Normal users (small follower counts)
        User alice = new User("alice", "Alice Johnson", "alice@example.com");
        User bob = new User("bob", "Bob Smith", "bob@example.com");
        User charlie = new User("charlie", "Charlie Brown", "charlie@example.com");
        User diana = new User("diana", "Diana Prince", "diana@example.com");
        User eve = new User("eve", "Eve Williams", "eve@example.com");

        // Celebrities (followerCount > 10,000 threshold)
        User elon = new User("elon", "Elon Musk", "elon@example.com");
        elon.setFollowerCount(50_000);  // Celebrity: fan-out on read

        User taylor = new User("taylor", "Taylor Swift", "taylor@example.com");
        taylor.setFollowerCount(100_000);  // Celebrity: fan-out on read

        User cristiano = new User("cristiano", "Cristiano Ronaldo", "cristiano@example.com");
        cristiano.setFollowerCount(200_000);  // Celebrity: fan-out on read

        // Save all users
        userRepository.save(alice);
        userRepository.save(bob);
        userRepository.save(charlie);
        userRepository.save(diana);
        userRepository.save(eve);
        userRepository.save(elon);
        userRepository.save(taylor);
        userRepository.save(cristiano);
    }

    // --- Getters for all components ---

    public UserRepository getUserRepository() {
        return userRepository;
    }

    public PostRepository getPostRepository() {
        return postRepository;
    }

    public FollowRepository getFollowRepository() {
        return followRepository;
    }

    public EngagementRepository getEngagementRepository() {
        return engagementRepository;
    }

    public TimelineCache getTimelineCache() {
        return timelineCache;
    }

    public HybridFanoutStrategy getHybridFanoutStrategy() {
        return hybridFanoutStrategy;
    }

    public AlgorithmicRankingStrategy getAlgorithmicRankingStrategy() {
        return algorithmicRankingStrategy;
    }

    public ChronologicalRankingStrategy getChronologicalRankingStrategy() {
        return chronologicalRankingStrategy;
    }

    public SocialGraphService getSocialGraphService() {
        return socialGraphService;
    }

    public NotificationService getNotificationService() {
        return notificationService;
    }

    public TimelineService getTimelineService() {
        return timelineService;
    }

    public RankingService getRankingService() {
        return rankingService;
    }

    public FanoutService getFanoutService() {
        return fanoutService;
    }

    public PostService getPostService() {
        return postService;
    }

    public EngagementService getEngagementService() {
        return engagementService;
    }

    public FeedService getFeedService() {
        return feedService;
    }

    public FeedController getFeedController() {
        return feedController;
    }

    public FeedStatsDisplay getFeedStatsDisplay() {
        return feedStatsDisplay;
    }
}
