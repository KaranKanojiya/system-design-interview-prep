package com.systemdesign.videostreaming.display;

import com.systemdesign.videostreaming.model.TranscodeJob;
import com.systemdesign.videostreaming.model.Video;
import com.systemdesign.videostreaming.model.VideoMetadata;
import com.systemdesign.videostreaming.repository.VideoRepository;
import com.systemdesign.videostreaming.service.AnalyticsService;
import com.systemdesign.videostreaming.service.CDNService;
import com.systemdesign.videostreaming.service.TranscodingService;

import java.util.List;
import java.util.Map;

/**
 * Displays platform-wide streaming statistics in a formatted view.
 *
 * In production: these metrics would be on a Grafana/Datadog dashboard.
 * Key metrics for a video platform:
 *   - Total videos / views (platform health)
 *   - CDN hit rate (infrastructure efficiency)
 *   - Average watch time (user engagement)
 *   - Transcoding throughput (pipeline health)
 */
public class StreamingStatsDisplay {

    private final VideoRepository videoRepository;
    private final AnalyticsService analyticsService;
    private final CDNService cdnService;
    private final TranscodingService transcodingService;
    private final Map<String, VideoMetadata> metadataMap;

    public StreamingStatsDisplay(VideoRepository videoRepository,
                                 AnalyticsService analyticsService,
                                 CDNService cdnService,
                                 TranscodingService transcodingService,
                                 Map<String, VideoMetadata> metadataMap) {
        this.videoRepository = videoRepository;
        this.analyticsService = analyticsService;
        this.cdnService = cdnService;
        this.transcodingService = transcodingService;
        this.metadataMap = metadataMap;
    }

    /**
     * Print a comprehensive stats summary.
     */
    public void printStats() {
        System.out.println("\n--- Platform Statistics ---");

        // Video counts
        List<Video> allVideos = videoRepository.findAll();
        System.out.println("Total videos: " + allVideos.size());
        long readyCount = allVideos.stream()
                .filter(v -> v.getStatus() == com.systemdesign.videostreaming.model.VideoStatus.READY)
                .count();
        System.out.println("Ready to stream: " + readyCount);

        // View stats
        long totalViews = analyticsService.getTotalViews();
        System.out.println("Total views: " + totalViews);
        System.out.println("Total watch time: " + analyticsService.getTotalWatchTimeMinutes() + " minutes");
        System.out.printf("Average watch time: %.1f seconds%n", analyticsService.getAverageWatchTimeSeconds());

        // CDN stats
        System.out.println("\n--- CDN Performance ---");
        System.out.println("Cache size: " + cdnService.getCacheSize() + " / " + cdnService.getMaxCacheSize());
        System.out.println("Total requests: " + cdnService.getTotalRequests());
        System.out.println("Cache hits: " + cdnService.getCacheHits());
        System.out.println("Cache misses: " + cdnService.getCacheMisses());
        System.out.printf("Hit rate: %.1f%%%n", cdnService.getHitRate());

        // Transcoding stats
        System.out.println("\n--- Transcoding ---");
        int totalJobs = 0;
        int completedJobs = 0;
        int failedJobs = 0;
        for (Video video : allVideos) {
            List<TranscodeJob> jobs = transcodingService.getJobsForVideo(video.getVideoId());
            totalJobs += jobs.size();
            completedJobs += jobs.stream()
                    .filter(j -> j.getStatus() == com.systemdesign.videostreaming.model.TranscodeJobStatus.COMPLETED)
                    .count();
            failedJobs += jobs.stream()
                    .filter(j -> j.getStatus() == com.systemdesign.videostreaming.model.TranscodeJobStatus.FAILED)
                    .count();
        }
        System.out.println("Total transcode jobs: " + totalJobs);
        System.out.println("Completed: " + completedJobs);
        System.out.println("Failed: " + failedJobs);
    }
}
