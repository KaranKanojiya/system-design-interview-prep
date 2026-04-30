package com.systemdesign.videostreaming.config;

import com.systemdesign.videostreaming.controller.VideoController;
import com.systemdesign.videostreaming.display.StreamingStatsDisplay;
import com.systemdesign.videostreaming.model.*;
import com.systemdesign.videostreaming.repository.*;
import com.systemdesign.videostreaming.service.*;
import com.systemdesign.videostreaming.store.InMemoryVideoStore;
import com.systemdesign.videostreaming.store.VideoStore;
import com.systemdesign.videostreaming.strategy.recommendation.PersonalizedRecommendation;
import com.systemdesign.videostreaming.strategy.recommendation.RecommendationStrategy;
import com.systemdesign.videostreaming.strategy.recommendation.TrendingRecommendation;
import com.systemdesign.videostreaming.strategy.streaming.ABRStrategy;
import com.systemdesign.videostreaming.strategy.streaming.BufferBasedABR;
import com.systemdesign.videostreaming.strategy.streaming.ThroughputBasedABR;
import com.systemdesign.videostreaming.strategy.transcoding.ParallelTranscodingStrategy;
import com.systemdesign.videostreaming.strategy.transcoding.SequentialTranscodingStrategy;
import com.systemdesign.videostreaming.strategy.transcoding.TranscodingStrategy;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FACTORY + Dependency Injection container.
 *
 * This is the ONLY place in the application where concrete implementations
 * are instantiated with "new ConcreteClass()". Every other class depends on
 * interfaces, making the system testable and swappable.
 *
 * Dependency graph (→ = "depends on"):
 *
 *   VideoController → VideoService (facade)
 *                   → RecommendationService
 *
 *   VideoService → VideoRepository
 *               → UploadService → VideoStore
 *               → TranscodingService → TranscodingStrategy
 *               → StreamingService → ABRStrategy
 *               → AnalyticsService → WatchHistoryRepository, VideoRepository, MetadataMap
 *               → SearchService → VideoRepository, MetadataMap
 *
 *   RecommendationService → RecommendationStrategy
 *     TrendingRecommendation → VideoRepository, MetadataMap
 *     PersonalizedRecommendation → VideoRepository, WatchHistoryRepository, MetadataMap
 *
 *   CDNService → VideoStore (origin)
 *
 *   StreamingStatsDisplay → VideoRepository, AnalyticsService, CDNService, TranscodingService
 *
 * In production: Spring Boot auto-wires this via @Configuration / @Bean / @Autowired.
 */
public class AppConfig {

    // ─── Shared State ───────────────────────────────────────────────────
    // These are shared across services (simulating a shared database)

    private final Map<String, VideoMetadata> metadataMap = new ConcurrentHashMap<>();

    // ─── Repositories ───────────────────────────────────────────────────

    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    private final WatchHistoryRepository watchHistoryRepository;

    // ─── Stores ─────────────────────────────────────────────────────────

    private final VideoStore videoStore;

    // ─── Strategies ─────────────────────────────────────────────────────

    private final ParallelTranscodingStrategy parallelTranscodingStrategy;
    private final SequentialTranscodingStrategy sequentialTranscodingStrategy;
    private final ThroughputBasedABR throughputBasedABR;
    private final BufferBasedABR bufferBasedABR;
    private final TrendingRecommendation trendingRecommendation;
    private final PersonalizedRecommendation personalizedRecommendation;

    // ─── Services ───────────────────────────────────────────────────────

    private final UploadService uploadService;
    private final TranscodingService parallelTranscodingService;
    private final TranscodingService sequentialTranscodingService;
    private final StreamingService throughputStreamingService;
    private final StreamingService bufferStreamingService;
    private final CDNService cdnService;
    private final AnalyticsService analyticsService;
    private final SearchService searchService;
    private final RecommendationService recommendationService;
    private final VideoService videoService;

    // ─── Controller ─────────────────────────────────────────────────────

    private final VideoController videoController;

    // ─── Display ────────────────────────────────────────────────────────

    private final StreamingStatsDisplay statsDisplay;

    public AppConfig() {
        // ── Layer 1: Repositories (no dependencies) ──
        this.videoRepository = new InMemoryVideoRepository();
        this.userRepository = new InMemoryUserRepository();
        this.watchHistoryRepository = new InMemoryWatchHistoryRepository();

        // ── Layer 2: Stores (no dependencies) ──
        this.videoStore = new InMemoryVideoStore();

        // ── Layer 3: Strategies (may depend on repositories) ──
        this.parallelTranscodingStrategy = new ParallelTranscodingStrategy(6); // 6 threads = 6 resolutions
        this.sequentialTranscodingStrategy = new SequentialTranscodingStrategy();
        this.throughputBasedABR = new ThroughputBasedABR();
        this.bufferBasedABR = new BufferBasedABR();
        this.trendingRecommendation = new TrendingRecommendation(videoRepository, metadataMap);
        this.personalizedRecommendation = new PersonalizedRecommendation(
                videoRepository, watchHistoryRepository, metadataMap);

        // ── Layer 4: Services (depend on repositories + strategies) ──
        this.uploadService = new UploadService(videoStore);
        this.parallelTranscodingService = new TranscodingService(parallelTranscodingStrategy);
        this.sequentialTranscodingService = new TranscodingService(sequentialTranscodingStrategy);
        this.throughputStreamingService = new StreamingService(throughputBasedABR);
        this.bufferStreamingService = new StreamingService(bufferBasedABR);
        this.cdnService = new CDNService(videoStore, 100); // Cache up to 100 chunks
        this.analyticsService = new AnalyticsService(watchHistoryRepository, videoRepository, metadataMap);
        this.searchService = new SearchService(videoRepository, metadataMap);
        this.recommendationService = new RecommendationService(trendingRecommendation);

        // ── Layer 5: Facade (depends on services) ──
        // Default: parallel transcoding + throughput-based ABR
        this.videoService = new VideoService(
                videoRepository, uploadService, parallelTranscodingService,
                throughputStreamingService, analyticsService, searchService);

        // ── Layer 6: Controller (depends on facade) ──
        this.videoController = new VideoController(videoService, recommendationService);

        // ── Layer 7: Display (depends on multiple services) ──
        this.statsDisplay = new StreamingStatsDisplay(
                videoRepository, analyticsService, cdnService,
                parallelTranscodingService, metadataMap);

        // ── Seed Data ──
        seedUsers();
        seedVideos();
    }

    // ─── Seed Data ──────────────────────────────────────────────────────

    private void seedUsers() {
        userRepository.save(new User("user_alice", "Alice", "alice@example.com"));
        userRepository.save(new User("user_bob", "Bob", "bob@example.com"));
        userRepository.save(new User("user_carol", "Carol", "carol@example.com"));
    }

    /**
     * Seed sample videos that are READY (pre-uploaded and transcoded).
     * These videos are used by search, recommendation, and analytics demos.
     */
    private void seedVideos() {
        // Video 1: Tech tutorial (very popular)
        createSeedVideo("vid_java101", "Java 21 Virtual Threads Tutorial",
                "Learn virtual threads in Java 21", "user_alice", "Alice",
                600, 150_000_000L, Resolution.P1080,
                List.of("java", "tutorial", "concurrency", "virtual-threads"),
                "Education", "en", 50_000, 4_500);

        // Video 2: Music video (extremely popular)
        createSeedVideo("vid_music01", "Summer Vibes Official Music Video",
                "Hit song of the summer", "user_bob", "Bob",
                240, 80_000_000L, Resolution.P4K,
                List.of("music", "pop", "summer", "official"),
                "Music", "en", 1_000_000, 85_000);

        // Video 3: Cooking tutorial (moderately popular)
        createSeedVideo("vid_cook01", "Perfect Pasta Carbonara Recipe",
                "Authentic Italian carbonara step by step", "user_carol", "Carol",
                900, 200_000_000L, Resolution.P1080,
                List.of("cooking", "pasta", "italian", "recipe"),
                "Food", "en", 25_000, 3_200);

        // Video 4: Tech review (popular)
        createSeedVideo("vid_tech01", "MacBook Pro M4 Review",
                "Full review of the new MacBook Pro", "user_alice", "Alice",
                1200, 300_000_000L, Resolution.P4K,
                List.of("tech", "review", "apple", "macbook", "laptop"),
                "Technology", "en", 120_000, 15_000);

        // Video 5: Gaming (moderate)
        createSeedVideo("vid_game01", "Elden Ring Boss Guide",
                "How to defeat every boss", "user_bob", "Bob",
                1800, 500_000_000L, Resolution.P1080,
                List.of("gaming", "elden-ring", "guide", "boss"),
                "Gaming", "en", 35_000, 5_000);

        // Video 6: Education (niche, low views)
        createSeedVideo("vid_math01", "Linear Algebra Explained Simply",
                "Vectors, matrices, and eigenvalues for beginners", "user_carol", "Carol",
                2400, 400_000_000L, Resolution.P720,
                List.of("math", "linear-algebra", "education"),
                "Education", "en", 8_000, 1_200);

        // Video 7: Travel vlog (moderate)
        createSeedVideo("vid_travel01", "Tokyo Street Food Tour",
                "Best street food in Shibuya and Shinjuku", "user_alice", "Alice",
                1500, 350_000_000L, Resolution.P4K,
                List.of("travel", "tokyo", "japan", "food", "vlog"),
                "Travel", "en", 45_000, 6_000);

        // Video 8: Fitness (popular)
        createSeedVideo("vid_fit01", "30 Minute Full Body Workout",
                "No equipment needed, suitable for all levels", "user_bob", "Bob",
                1800, 250_000_000L, Resolution.P1080,
                List.of("fitness", "workout", "home", "exercise"),
                "Fitness", "en", 75_000, 9_000);
    }

    private void createSeedVideo(String videoId, String title, String description,
                                  String uploaderId, String uploaderName,
                                  int durationSeconds, long fileSizeBytes,
                                  Resolution originalResolution,
                                  List<String> tags, String category, String language,
                                  long viewCount, long likeCount) {
        // Build available resolutions (up to original)
        List<Resolution> available = new ArrayList<>();
        for (Resolution res : Resolution.values()) {
            if (res.getHeight() <= originalResolution.getHeight()) {
                available.add(res);
            }
        }

        Video video = new Video.Builder(videoId, title, uploaderId)
                .description(description)
                .uploaderName(uploaderName)
                .status(VideoStatus.UPLOADING) // Will transition through states
                .durationSeconds(durationSeconds)
                .fileSizeBytes(fileSizeBytes)
                .originalResolution(originalResolution)
                .availableResolutions(available)
                .codec(Codec.H264)
                .createdAt(LocalDateTime.now().minusDays((long) (Math.random() * 30)))
                .build();

        // Fast-forward through state machine to READY
        video.markUploaded();
        video.startTranscoding();
        video.markReady();

        videoRepository.save(video);

        // Create metadata with view/like counts
        VideoMetadata metadata = new VideoMetadata(videoId, tags, category, language);
        metadata.setViewCount(viewCount);
        metadata.setLikeCount(likeCount);
        metadataMap.put(videoId, metadata);
    }

    // ─── Getters ────────────────────────────────────────────────────────
    // Provide access to all wired components (used by the demo app)

    public VideoRepository getVideoRepository() { return videoRepository; }
    public UserRepository getUserRepository() { return userRepository; }
    public WatchHistoryRepository getWatchHistoryRepository() { return watchHistoryRepository; }
    public VideoStore getVideoStore() { return videoStore; }
    public Map<String, VideoMetadata> getMetadataMap() { return metadataMap; }

    public ParallelTranscodingStrategy getParallelTranscodingStrategy() { return parallelTranscodingStrategy; }
    public SequentialTranscodingStrategy getSequentialTranscodingStrategy() { return sequentialTranscodingStrategy; }
    public ThroughputBasedABR getThroughputBasedABR() { return throughputBasedABR; }
    public BufferBasedABR getBufferBasedABR() { return bufferBasedABR; }
    public TrendingRecommendation getTrendingRecommendation() { return trendingRecommendation; }
    public PersonalizedRecommendation getPersonalizedRecommendation() { return personalizedRecommendation; }

    public UploadService getUploadService() { return uploadService; }
    public TranscodingService getParallelTranscodingService() { return parallelTranscodingService; }
    public TranscodingService getSequentialTranscodingService() { return sequentialTranscodingService; }
    public StreamingService getThroughputStreamingService() { return throughputStreamingService; }
    public StreamingService getBufferStreamingService() { return bufferStreamingService; }
    public CDNService getCdnService() { return cdnService; }
    public AnalyticsService getAnalyticsService() { return analyticsService; }
    public SearchService getSearchService() { return searchService; }
    public RecommendationService getRecommendationService() { return recommendationService; }
    public VideoService getVideoService() { return videoService; }
    public VideoController getVideoController() { return videoController; }
    public StreamingStatsDisplay getStatsDisplay() { return statsDisplay; }
}
