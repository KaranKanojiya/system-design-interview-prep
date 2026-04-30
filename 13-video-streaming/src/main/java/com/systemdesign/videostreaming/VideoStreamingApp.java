package com.systemdesign.videostreaming;

import com.systemdesign.videostreaming.config.AppConfig;
import com.systemdesign.videostreaming.model.*;
import com.systemdesign.videostreaming.service.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Video Streaming Platform — Main Demo Application
 *
 * Demonstrates core system design concepts for a YouTube/Netflix-like platform:
 *   - Chunked upload with resume
 *   - Parallel transcoding pipeline (ExecutorService)
 *   - Adaptive bitrate streaming (ABR)
 *   - CDN caching with LRU eviction
 *   - Recommendation strategies (trending vs personalized)
 *   - Video lifecycle state machine
 *   - Codec comparison
 *
 * Architecture:
 *   Client → Controller → VideoService (Facade) → {Upload, Transcode, Stream, CDN, Analytics, Search}
 *              ↓                                              ↓
 *          REST API                                     Repositories + Stores
 *
 * Run: gradle run or java VideoStreamingApp
 */
public class VideoStreamingApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("  VIDEO STREAMING PLATFORM — System Design Demo");
        System.out.println("  (YouTube/Netflix Architecture)");
        System.out.println(SEPARATOR);

        // ── Wire everything via AppConfig (the DI container) ──
        AppConfig config = new AppConfig();

        // Run all demos
        demo1_ChunkedUpload(config);
        demo2_TranscodingPipeline(config);
        demo3_ResolutionLadder(config);
        demo4_AdaptiveBitrateStreaming(config);
        demo5_ABRStrategyComparison(config);
        demo6_CDNCacheSimulation(config);
        demo7_VideoSearch(config);
        demo8_RecommendationComparison(config);
        demo9_WatchHistoryAnalytics(config);
        demo10_CodecComparison(config);
        demo11_VideoLifecycle(config);

        // Final summary
        printDesignSummary();

        // Cleanup
        config.getParallelTranscodingStrategy().shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 1: Chunked Upload with Resume
    // ─────────────────────────────────────────────────────────────────────

    private static void demo1_ChunkedUpload(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 1: Chunked Video Upload with Resume");
        System.out.println(SEPARATOR);

        UploadService uploadService = config.getUploadService();

        // Create a new video (500MB file)
        String videoId = "vid_upload_demo";
        long fileSize = 500_000_000L; // 500 MB
        Video video = new Video.Builder(videoId, "Upload Demo Video", "user_alice")
                .fileSizeBytes(fileSize)
                .originalResolution(Resolution.P1080)
                .durationSeconds(300)
                .build();
        config.getVideoRepository().save(video);

        // Step 1: Initiate upload → get chunk plan
        int totalChunks = uploadService.initiateUpload(video);
        System.out.println("\nFile size: " + (fileSize / 1_000_000) + " MB");
        System.out.println("Chunk size: 5 MB");
        System.out.println("Total chunks needed: " + totalChunks);

        // Step 2: Upload first 60% of chunks (simulating partial upload)
        int partialCount = (int) (totalChunks * 0.6);
        System.out.println("\n--- Uploading first " + partialCount + " chunks (60%) ---");
        for (int i = 0; i < partialCount; i++) {
            byte[] data = new byte[1024]; // Small simulated chunk data
            uploadService.uploadChunk(videoId, i, data);
        }
        System.out.printf("Progress: %.1f%% (%d/%d chunks)%n",
                uploadService.getUploadProgress(videoId), partialCount, totalChunks);
        System.out.println("Upload complete? " + uploadService.isUploadComplete(videoId));

        // Step 3: Simulate connection drop + resume
        System.out.println("\n--- Connection dropped! Resuming upload... ---");
        List<Integer> missingChunks = uploadService.getMissingChunks(videoId);
        System.out.println("Missing chunks: " + missingChunks.size() + " (indexes: "
                + missingChunks.stream().limit(5).map(String::valueOf).collect(Collectors.joining(", "))
                + (missingChunks.size() > 5 ? "..." : "") + ")");

        // Step 4: Resume by uploading only missing chunks
        System.out.println("Resuming from chunk " + missingChunks.get(0) + "...");
        for (int idx : missingChunks) {
            byte[] data = new byte[1024];
            uploadService.uploadChunk(videoId, idx, data);
        }
        System.out.printf("Progress: %.1f%% (%d/%d chunks)%n",
                uploadService.getUploadProgress(videoId),
                uploadService.getUploadedChunkIndexes(videoId).size(), totalChunks);
        System.out.println("Upload complete? " + uploadService.isUploadComplete(videoId));

        // Step 5: Mark video as uploaded
        video.markUploaded();
        System.out.println("Video status: " + video.getStatus());
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 2: Transcoding Pipeline (Parallel vs Sequential)
    // ─────────────────────────────────────────────────────────────────────

    private static void demo2_TranscodingPipeline(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 2: Transcoding Pipeline (Parallel vs Sequential)");
        System.out.println(SEPARATOR);

        // Create a fresh video for transcoding demo
        Video parallelVideo = new Video.Builder("vid_transcode_p", "Parallel Transcode Test", "user_alice")
                .fileSizeBytes(200_000_000L)
                .originalResolution(Resolution.P1080)
                .durationSeconds(600)
                .build();
        parallelVideo.markUploaded();
        config.getVideoRepository().save(parallelVideo);

        Video sequentialVideo = new Video.Builder("vid_transcode_s", "Sequential Transcode Test", "user_bob")
                .fileSizeBytes(200_000_000L)
                .originalResolution(Resolution.P1080)
                .durationSeconds(600)
                .build();
        sequentialVideo.markUploaded();
        config.getVideoRepository().save(sequentialVideo);

        // Parallel transcoding
        System.out.println("\n--- Parallel Transcoding (6 threads) ---");
        long parallelStart = System.currentTimeMillis();
        List<TranscodeJob> parallelJobs = config.getParallelTranscodingService().transcodeVideo(parallelVideo);
        long parallelTime = System.currentTimeMillis() - parallelStart;

        for (TranscodeJob job : parallelJobs) {
            System.out.println("  " + job.getTargetResolution().getLabel() + " → " + job.getStatus()
                    + " (progress: " + job.getProgressPercent() + "%)");
        }
        System.out.println("Parallel time: " + parallelTime + " ms");
        System.out.println("Video status: " + parallelVideo.getStatus());

        // Sequential transcoding
        System.out.println("\n--- Sequential Transcoding (1 thread) ---");
        long sequentialStart = System.currentTimeMillis();
        List<TranscodeJob> sequentialJobs = config.getSequentialTranscodingService().transcodeVideo(sequentialVideo);
        long sequentialTime = System.currentTimeMillis() - sequentialStart;

        for (TranscodeJob job : sequentialJobs) {
            System.out.println("  " + job.getTargetResolution().getLabel() + " → " + job.getStatus()
                    + " (progress: " + job.getProgressPercent() + "%)");
        }
        System.out.println("Sequential time: " + sequentialTime + " ms");
        System.out.println("Video status: " + sequentialVideo.getStatus());

        // Comparison
        System.out.println("\n--- Comparison ---");
        System.out.println("Parallel:   " + parallelTime + " ms");
        System.out.println("Sequential: " + sequentialTime + " ms");
        if (sequentialTime > 0) {
            System.out.printf("Speedup:    %.1fx faster with parallel transcoding%n",
                    (double) sequentialTime / Math.max(1, parallelTime));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 3: Resolution Ladder
    // ─────────────────────────────────────────────────────────────────────

    private static void demo3_ResolutionLadder(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 3: Resolution Ladder");
        System.out.println(SEPARATOR);

        System.out.println("\nAll available resolutions and their bitrate requirements:");
        System.out.println(String.format("  %-8s %-12s %-15s %-10s",
                "Label", "Dimensions", "Bitrate", "Bandwidth needed"));
        System.out.println("  " + "-".repeat(50));

        for (Resolution res : Resolution.values()) {
            System.out.printf("  %-8s %-12s %-15s %-10s%n",
                    res.getLabel(),
                    res.getWidth() + "x" + res.getDisplayHeight(),
                    String.format("%,d Kbps", res.getBitrateKbps()),
                    String.format("%.1f Mbps", res.getBitrateKbps() / 1000.0));
        }

        // Show ladder for a specific video
        Video video = config.getVideoRepository().findById("vid_java101").orElse(null);
        if (video != null) {
            System.out.println("\nResolution ladder for '" + video.getTitle() + "' (source: "
                    + video.getOriginalResolution().getLabel() + "):");
            List<Resolution> ladder = config.getParallelTranscodingService()
                    .buildResolutionLadder(video.getOriginalResolution());
            for (Resolution res : ladder) {
                System.out.println("  → " + res);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 4: Adaptive Bitrate Streaming
    // ─────────────────────────────────────────────────────────────────────

    private static void demo4_AdaptiveBitrateStreaming(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 4: Adaptive Bitrate Streaming (Throughput-Based ABR)");
        System.out.println(SEPARATOR);

        Video video = config.getVideoRepository().findById("vid_music01").orElse(null);
        if (video == null) {
            System.out.println("ERROR: Seed video not found");
            return;
        }

        System.out.println("\nStreaming '" + video.getTitle() + "' at ~5 Mbps bandwidth:");
        System.out.println("Available resolutions: " + video.getAvailableResolutions().stream()
                .map(Resolution::getLabel).collect(Collectors.joining(", ")));

        StreamingService streamingService = config.getThroughputStreamingService();
        List<String> log = streamingService.simulateStreaming(video, "user_alice", 5_000_000, 40);
        for (String entry : log) {
            System.out.println(entry);
        }

        // Record the view
        config.getAnalyticsService().recordView(video.getVideoId(), "user_alice", 40);
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 5: ABR Strategy Comparison
    // ─────────────────────────────────────────────────────────────────────

    private static void demo5_ABRStrategyComparison(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 5: ABR Strategy Comparison");
        System.out.println(SEPARATOR);

        Video video = config.getVideoRepository().findById("vid_tech01").orElse(null);
        if (video == null) {
            System.out.println("ERROR: Seed video not found");
            return;
        }

        long bandwidth = 4_000_000; // 4 Mbps — will force quality trade-offs
        int duration = 32; // 32 seconds = 8 segments

        // Throughput-based ABR
        System.out.println("\n--- Throughput-Based ABR ---");
        System.out.println("Strategy: " + config.getThroughputBasedABR());
        StreamingService throughputService = config.getThroughputStreamingService();
        List<String> throughputLog = throughputService.simulateStreaming(video, "user_bob", bandwidth, duration);
        for (String entry : throughputLog) {
            System.out.println(entry);
        }

        // Buffer-based ABR
        System.out.println("\n--- Buffer-Based ABR (Netflix BBA) ---");
        System.out.println("Strategy: " + config.getBufferBasedABR());
        StreamingService bufferService = config.getBufferStreamingService();
        List<String> bufferLog = bufferService.simulateStreaming(video, "user_bob", bandwidth, duration);
        for (String entry : bufferLog) {
            System.out.println(entry);
        }

        System.out.println("\nKey difference: Buffer-based is more conservative, preferring lower");
        System.out.println("resolutions when buffer is low to prevent rebuffering.");
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 6: CDN Cache Simulation
    // ─────────────────────────────────────────────────────────────────────

    private static void demo6_CDNCacheSimulation(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 6: CDN Cache Simulation (Hit/Miss)");
        System.out.println(SEPARATOR);

        CDNService cdn = config.getCdnService();
        cdn.resetStats();

        // First, store some chunks in the origin (simulating post-transcode storage)
        String popularVideoId = "vid_cdn_popular";
        String rareVideoId = "vid_cdn_rare";

        // Store chunks for both videos in origin
        for (int i = 0; i < 5; i++) {
            VideoChunk popularChunk = new VideoChunk(
                    popularVideoId + "_chunk_" + i, popularVideoId, i,
                    5_000_000L, Resolution.P1080,
                    "s3://chunks/" + popularVideoId + "/" + i, "checksum_" + i);
            config.getVideoStore().storeChunk(popularChunk);

            VideoChunk rareChunk = new VideoChunk(
                    rareVideoId + "_chunk_" + i, rareVideoId, i,
                    5_000_000L, Resolution.P1080,
                    "s3://chunks/" + rareVideoId + "/" + i, "checksum_" + i);
            config.getVideoStore().storeChunk(rareChunk);
        }

        System.out.println("\n--- Popular video: 3 users watch the same video ---");
        // User 1: all misses (first access)
        System.out.println("User 1 (first viewer):");
        long start1 = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            cdn.getChunk(popularVideoId, Resolution.P1080, i);
        }
        long time1 = System.currentTimeMillis() - start1;
        System.out.printf("  Fetched 5 chunks in %d ms (all cache MISSES)%n", time1);

        // User 2: all hits (content is now cached)
        System.out.println("User 2 (second viewer):");
        long start2 = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            cdn.getChunk(popularVideoId, Resolution.P1080, i);
        }
        long time2 = System.currentTimeMillis() - start2;
        System.out.printf("  Fetched 5 chunks in %d ms (all cache HITS)%n", time2);

        // User 3: all hits
        System.out.println("User 3 (third viewer):");
        long start3 = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            cdn.getChunk(popularVideoId, Resolution.P1080, i);
        }
        long time3 = System.currentTimeMillis() - start3;
        System.out.printf("  Fetched 5 chunks in %d ms (all cache HITS)%n", time3);

        System.out.println("\n--- Rare video: first and only viewer ---");
        long start4 = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            cdn.getChunk(rareVideoId, Resolution.P1080, i);
        }
        long time4 = System.currentTimeMillis() - start4;
        System.out.printf("  Fetched 5 chunks in %d ms (all cache MISSES)%n", time4);

        System.out.println("\n--- CDN Stats ---");
        System.out.println("Total requests: " + cdn.getTotalRequests());
        System.out.println("Cache hits: " + cdn.getCacheHits());
        System.out.println("Cache misses: " + cdn.getCacheMisses());
        System.out.printf("Hit rate: %.1f%%%n", cdn.getHitRate());
        System.out.println("Cache size: " + cdn.getCacheSize() + " / " + cdn.getMaxCacheSize() + " chunks");
        System.out.println("\nInsight: Popular content has high cache hit rate, reducing origin load.");
        System.out.println("Rare content always misses, requiring origin fetches (slower).");
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 7: Video Search
    // ─────────────────────────────────────────────────────────────────────

    private static void demo7_VideoSearch(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 7: Video Search");
        System.out.println(SEPARATOR);

        SearchService search = config.getSearchService();

        // Search by title
        System.out.println("\n--- Search by title: 'java' ---");
        List<Video> titleResults = search.searchByTitle("java");
        printSearchResults(titleResults);

        System.out.println("\n--- Search by title: 'review' ---");
        titleResults = search.searchByTitle("review");
        printSearchResults(titleResults);

        // Search by tag
        System.out.println("\n--- Search by tag: 'cooking' ---");
        List<Video> tagResults = search.searchByTag("cooking");
        printSearchResults(tagResults);

        System.out.println("\n--- Search by tag: 'tutorial' ---");
        tagResults = search.searchByTag("tutorial");
        printSearchResults(tagResults);

        // Search by category
        System.out.println("\n--- Search by category: 'Education' ---");
        List<Video> catResults = search.searchByCategory("Education");
        printSearchResults(catResults);

        System.out.println("\n--- Search by category: 'Music' ---");
        catResults = search.searchByCategory("Music");
        printSearchResults(catResults);
    }

    private static void printSearchResults(List<Video> results) {
        if (results.isEmpty()) {
            System.out.println("  No results found.");
        } else {
            for (Video v : results) {
                System.out.println("  - " + v.getTitle() + " [" + v.getVideoId() + "] ("
                        + v.getOriginalResolution().getLabel() + ")");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 8: Recommendation Comparison
    // ─────────────────────────────────────────────────────────────────────

    private static void demo8_RecommendationComparison(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 8: Recommendation Comparison");
        System.out.println(SEPARATOR);

        // First, create some watch history for personalization
        AnalyticsService analytics = config.getAnalyticsService();
        analytics.recordView("vid_java101", "user_alice", 500);  // Education
        analytics.recordView("vid_math01", "user_alice", 2000);  // Education
        analytics.recordView("vid_tech01", "user_alice", 1000);  // Technology
        analytics.recordView("vid_music01", "user_bob", 200);    // Music
        analytics.recordView("vid_game01", "user_bob", 1500);    // Gaming

        RecommendationService recService = config.getRecommendationService();

        // Trending recommendations (same for everyone)
        System.out.println("\n--- Trending Recommendations (for any user) ---");
        System.out.println("Strategy: " + config.getTrendingRecommendation());
        recService.setStrategy(config.getTrendingRecommendation());
        List<Video> trending = recService.recommend("user_alice", 5);
        for (int i = 0; i < trending.size(); i++) {
            Video v = trending.get(i);
            VideoMetadata meta = config.getMetadataMap().get(v.getVideoId());
            long views = meta != null ? meta.getViewCount() : 0;
            System.out.printf("  %d. %s (%,d views)%n", i + 1, v.getTitle(), views);
        }

        // Personalized for Alice (prefers Education/Technology)
        System.out.println("\n--- Personalized for Alice (Education/Tech viewer) ---");
        System.out.println("Strategy: " + config.getPersonalizedRecommendation());
        recService.setStrategy(config.getPersonalizedRecommendation());
        List<Video> personalizedAlice = recService.recommend("user_alice", 5);
        for (int i = 0; i < personalizedAlice.size(); i++) {
            Video v = personalizedAlice.get(i);
            VideoMetadata meta = config.getMetadataMap().get(v.getVideoId());
            String category = meta != null ? meta.getCategory() : "Unknown";
            System.out.printf("  %d. %s (category: %s)%n", i + 1, v.getTitle(), category);
        }

        // Personalized for Bob (prefers Music/Gaming)
        System.out.println("\n--- Personalized for Bob (Music/Gaming viewer) ---");
        List<Video> personalizedBob = recService.recommend("user_bob", 5);
        for (int i = 0; i < personalizedBob.size(); i++) {
            Video v = personalizedBob.get(i);
            VideoMetadata meta = config.getMetadataMap().get(v.getVideoId());
            String category = meta != null ? meta.getCategory() : "Unknown";
            System.out.printf("  %d. %s (category: %s)%n", i + 1, v.getTitle(), category);
        }

        System.out.println("\nInsight: Trending is the same for everyone (cold start fallback).");
        System.out.println("Personalized surfaces content matching user's viewing history.");
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 9: Watch History & Analytics
    // ─────────────────────────────────────────────────────────────────────

    private static void demo9_WatchHistoryAnalytics(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 9: Watch History & Analytics");
        System.out.println(SEPARATOR);

        AnalyticsService analytics = config.getAnalyticsService();

        // Record some additional views
        analytics.recordView("vid_music01", "user_carol", 180);
        analytics.recordView("vid_cook01", "user_alice", 800);
        analytics.recordView("vid_fit01", "user_carol", 1600);
        analytics.recordView("vid_travel01", "user_bob", 900);

        // View counts
        System.out.println("\n--- View Counts ---");
        List<Map.Entry<String, Long>> mostWatched = analytics.getMostWatched(8);
        for (Map.Entry<String, Long> entry : mostWatched) {
            String videoId = entry.getKey();
            Video video = config.getVideoRepository().findById(videoId).orElse(null);
            String title = video != null ? video.getTitle() : videoId;
            System.out.printf("  %s: %,d views%n", title, entry.getValue());
        }

        // Watch history for Alice
        System.out.println("\n--- Alice's Watch History ---");
        List<WatchHistory> aliceHistory = analytics.getWatchHistory("user_alice");
        for (WatchHistory wh : aliceHistory) {
            System.out.printf("  %s — watched %ds/%ds (%.1f%% complete)%n",
                    wh.getVideoTitle(), wh.getWatchDurationSeconds(),
                    wh.getTotalDurationSeconds(), wh.getCompletionPercent());
        }

        // Platform-wide stats
        System.out.println("\n--- Platform Analytics ---");
        System.out.println("Total views: " + analytics.getTotalViews());
        System.out.println("Total watch time: " + analytics.getTotalWatchTimeMinutes() + " minutes");
        System.out.printf("Average session: %.1f seconds%n", analytics.getAverageWatchTimeSeconds());
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 10: Codec Comparison
    // ─────────────────────────────────────────────────────────────────────

    private static void demo10_CodecComparison(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 10: Codec Comparison");
        System.out.println(SEPARATOR);

        long h264BaselineSize = 500_000_000L; // 500 MB in H264

        System.out.println("\nFor a 500 MB video (H264 baseline):");
        System.out.println(String.format("  %-8s %-20s %-15s %-15s %-10s",
                "Codec", "Description", "Ratio", "File Size", "Savings"));
        System.out.println("  " + "-".repeat(68));

        for (Codec codec : Codec.values()) {
            long estimatedSize = codec.estimateFileSize(h264BaselineSize);
            long savings = h264BaselineSize - estimatedSize;
            double savingsPercent = (savings * 100.0) / h264BaselineSize;

            System.out.printf("  %-8s %-20s %-15s %-15s %-10s%n",
                    codec.name(),
                    codec.getDescription(),
                    String.format("%.1fx", codec.getCompressionRatio()),
                    String.format("%d MB", estimatedSize / 1_000_000),
                    String.format("%.0f%% saved", savingsPercent));
        }

        System.out.println("\nBandwidth impact for 1080p streaming:");
        int baseBitrate = Resolution.P1080.getBitrateKbps();
        for (Codec codec : Codec.values()) {
            int adjustedBitrate = (int) (baseBitrate * codec.getCompressionRatio());
            System.out.printf("  %s: %,d Kbps (%.1f Mbps)%n",
                    codec.name(), adjustedBitrate, adjustedBitrate / 1000.0);
        }

        System.out.println("\nTrade-off: Newer codecs (AV1) save 60% bandwidth but");
        System.out.println("require 10-100x more CPU to encode. Worth it for popular videos.");
    }

    // ─────────────────────────────────────────────────────────────────────
    // DEMO 11: Video Lifecycle (State Machine)
    // ─────────────────────────────────────────────────────────────────────

    private static void demo11_VideoLifecycle(AppConfig config) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 11: Video Lifecycle State Machine");
        System.out.println(SEPARATOR);

        Video video = new Video.Builder("vid_lifecycle", "Lifecycle Demo Video", "user_alice")
                .fileSizeBytes(100_000_000L)
                .originalResolution(Resolution.P720)
                .durationSeconds(120)
                .build();

        System.out.println("\n--- Happy Path ---");
        System.out.println("Initial state:     " + video.getStatus());

        video.markUploaded();
        System.out.println("After upload:      " + video.getStatus());

        video.startTranscoding();
        System.out.println("Transcoding start: " + video.getStatus());

        video.markReady();
        System.out.println("Transcoding done:  " + video.getStatus());

        video.delete();
        System.out.println("After delete:      " + video.getStatus());

        // Invalid transitions
        System.out.println("\n--- Invalid Transitions (guarded) ---");

        Video video2 = new Video.Builder("vid_lifecycle2", "Guard Test Video", "user_bob")
                .fileSizeBytes(50_000_000L)
                .originalResolution(Resolution.P480)
                .durationSeconds(60)
                .build();

        // Try to start transcoding before upload (should fail)
        try {
            video2.startTranscoding();
            System.out.println("ERROR: Should have thrown exception!");
        } catch (IllegalStateException e) {
            System.out.println("BLOCKED: UPLOADING -> TRANSCODING: " + e.getMessage());
        }

        // Try to mark ready before transcoding (should fail)
        try {
            video2.markReady();
            System.out.println("ERROR: Should have thrown exception!");
        } catch (IllegalStateException e) {
            System.out.println("BLOCKED: UPLOADING -> READY: " + e.getMessage());
        }

        // Try to delete already-deleted video
        Video video3 = new Video.Builder("vid_lifecycle3", "Double Delete Test", "user_carol")
                .fileSizeBytes(50_000_000L)
                .originalResolution(Resolution.P480)
                .durationSeconds(60)
                .build();
        video3.markUploaded();
        video3.startTranscoding();
        video3.markReady();
        video3.delete();

        try {
            video3.delete();
            System.out.println("ERROR: Should have thrown exception!");
        } catch (IllegalStateException e) {
            System.out.println("BLOCKED: DELETED -> DELETED: " + e.getMessage());
        }

        // Retry after failure
        System.out.println("\n--- Failure + Retry ---");
        Video video4 = new Video.Builder("vid_lifecycle4", "Retry After Failure", "user_alice")
                .fileSizeBytes(50_000_000L)
                .originalResolution(Resolution.P480)
                .durationSeconds(60)
                .build();
        video4.markUploaded();
        video4.startTranscoding();
        System.out.println("Status: " + video4.getStatus());
        video4.markFailed();
        System.out.println("After failure: " + video4.getStatus());
        video4.startTranscoding(); // Retry allowed from FAILED
        System.out.println("Retry transcoding: " + video4.getStatus());
        video4.markReady();
        System.out.println("Finally ready: " + video4.getStatus());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Design Summary
    // ─────────────────────────────────────────────────────────────────────

    private static void printDesignSummary() {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DESIGN SUMMARY — Video Streaming Platform");
        System.out.println(SEPARATOR);
        System.out.println("""

                Architecture Patterns:
                  - Facade Pattern: VideoService wraps 6 internal services
                  - Strategy Pattern: Transcoding (parallel/sequential), ABR (throughput/buffer),
                    Recommendation (trending/personalized)
                  - Builder Pattern: Video entity with 12+ fields
                  - State Machine: Video lifecycle with guarded transitions
                  - Repository Pattern: Separate metadata (DB) from binary data (object store)

                Key Components:
                  - Upload:        Chunked upload with resume support (5MB chunks)
                  - Transcoding:   Resolution ladder, parallel pipeline (ExecutorService)
                  - Streaming:     HLS/DASH manifest, adaptive bitrate (ABR)
                  - CDN:           LRU cache, hit/miss tracking, origin fallback
                  - Search:        Title/tag/category with substring matching
                  - Recommendations: Trending (cold start) vs Personalized (history-based)
                  - Analytics:     View counts, watch history, completion rate

                Production Scaling:
                  - Upload:        Presigned S3 URLs, parallel chunk upload
                  - Transcoding:   Kubernetes pods with GPU, job queue (SQS/Kafka)
                  - Streaming:     CDN edge servers (CloudFront), segment caching
                  - Search:        Elasticsearch with full-text + semantic search
                  - Recommendations: ML pipeline (TensorFlow), feature store, A/B testing
                  - Analytics:     Kafka → Flink → ClickHouse, Grafana dashboards

                Codec Evolution: H264 (compatible) → H265 (50% savings) → AV1 (60% savings)
                ABR Evolution: Throughput-based (simple) → Buffer-based (Netflix BBA)
                """);
        System.out.println(SEPARATOR);
    }
}
