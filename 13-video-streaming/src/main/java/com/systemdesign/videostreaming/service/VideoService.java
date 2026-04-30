package com.systemdesign.videostreaming.service;

import com.systemdesign.videostreaming.exception.VideoNotFoundException;
import com.systemdesign.videostreaming.model.*;
import com.systemdesign.videostreaming.repository.VideoRepository;

import java.util.List;
import java.util.UUID;

/**
 * FACADE: single entry point for all video operations.
 *
 * Why a Facade?
 *   - Clients (controller, API gateway) interact with ONE service, not six
 *   - Facade coordinates multi-step workflows (upload → transcode → ready)
 *   - Simplifies testing: mock one facade instead of six services
 *   - Encapsulates the dependency graph between services
 *
 * Call chain for uploadVideo:
 *   VideoService.uploadVideo()
 *     → UploadService.initiateUpload()     (create chunk plan)
 *     → UploadService.uploadChunk() x N    (upload each chunk)
 *     → Video.markUploaded()               (state transition)
 *     → TranscodingService.transcodeVideo() (kick off transcoding)
 *       → TranscodingStrategy.transcode()  (parallel or sequential)
 *     → Video.markReady()                  (final state)
 *
 * Call chain for streamVideo:
 *   VideoService.streamVideo()
 *     → StreamingService.generateManifest()         (HLS/DASH manifest)
 *     → StreamingService.simulateStreaming()         (ABR simulation)
 *     → AnalyticsService.recordView()               (track watch event)
 */
public class VideoService {

    private final VideoRepository videoRepository;
    private final UploadService uploadService;
    private final TranscodingService transcodingService;
    private final StreamingService streamingService;
    private final AnalyticsService analyticsService;
    private final SearchService searchService;

    public VideoService(VideoRepository videoRepository,
                        UploadService uploadService,
                        TranscodingService transcodingService,
                        StreamingService streamingService,
                        AnalyticsService analyticsService,
                        SearchService searchService) {
        this.videoRepository = videoRepository;
        this.uploadService = uploadService;
        this.transcodingService = transcodingService;
        this.streamingService = streamingService;
        this.analyticsService = analyticsService;
        this.searchService = searchService;
    }

    /**
     * Full upload workflow: create video → chunk upload → transcode → ready.
     */
    public Video uploadVideo(String userId, String title, String description,
                             long fileSizeBytes, Resolution resolution) {
        // Step 1: Create the video entity
        String videoId = "vid_" + UUID.randomUUID().toString().substring(0, 8);
        Video video = new Video.Builder(videoId, title, userId)
                .description(description)
                .fileSizeBytes(fileSizeBytes)
                .originalResolution(resolution)
                .durationSeconds((int) (fileSizeBytes / 500_000)) // ~rough estimate for demo
                .codec(Codec.H264)
                .build();

        videoRepository.save(video);

        // Step 2: Initiate chunked upload
        int chunkCount = uploadService.initiateUpload(video);

        // Step 3: Simulate uploading all chunks
        long chunkSize = fileSizeBytes / chunkCount;
        for (int i = 0; i < chunkCount; i++) {
            byte[] simulatedData = new byte[(int) Math.min(chunkSize, 1024)]; // Small for demo
            uploadService.uploadChunk(videoId, i, simulatedData);
        }

        // Step 4: Mark upload complete → transition UPLOADING → UPLOADED
        video.markUploaded();

        // Step 5: Trigger transcoding → transition UPLOADED → TRANSCODING → READY
        transcodingService.transcodeVideo(video);

        return video;
    }

    /**
     * Stream a video: generate manifest, simulate playback, record analytics.
     */
    public List<String> streamVideo(String videoId, String userId, long bandwidthKbps) {
        Video video = getVideo(videoId);

        // Simulate a watch session (60 seconds)
        List<String> streamingLog = streamingService.simulateStreaming(
                video, userId, bandwidthKbps, 60);

        // Record the view
        analyticsService.recordView(videoId, userId, 60);

        return streamingLog;
    }

    /**
     * Get a video by ID. Throws VideoNotFoundException if not found.
     */
    public Video getVideo(String videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
    }

    /**
     * Search videos by title.
     */
    public List<Video> searchVideos(String query) {
        return searchService.searchByTitle(query);
    }

    /**
     * Delete a video (soft-delete).
     */
    public void deleteVideo(String videoId) {
        Video video = getVideo(videoId);
        video.delete();
    }

    // ─── Accessors for sub-services (used by controller/demo) ────────

    public UploadService getUploadService() { return uploadService; }
    public TranscodingService getTranscodingService() { return transcodingService; }
    public StreamingService getStreamingService() { return streamingService; }
    public AnalyticsService getAnalyticsService() { return analyticsService; }
    public SearchService getSearchService() { return searchService; }
}
