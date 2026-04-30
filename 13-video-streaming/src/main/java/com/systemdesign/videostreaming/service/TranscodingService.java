package com.systemdesign.videostreaming.service;

import com.systemdesign.videostreaming.exception.TranscodingException;
import com.systemdesign.videostreaming.model.*;
import com.systemdesign.videostreaming.strategy.transcoding.TranscodingStrategy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the transcoding pipeline for uploaded videos.
 *
 * Transcoding flow:
 *   1. Receive an UPLOADED video
 *   2. Build a "resolution ladder" — all resolutions from 240p up to the original
 *      (no point transcoding to higher than the source)
 *   3. Delegate to TranscodingStrategy (parallel or sequential)
 *   4. Track all jobs per video
 *   5. When all jobs complete → video.markReady()
 *
 * Why a resolution ladder?
 *   - Users on slow connections need 240p/360p options
 *   - Users on fast connections want 1080p/4K
 *   - ABR algorithm picks the best resolution dynamically per segment
 *   - No point transcoding UP (e.g., 720p source → 4K) — just wastes storage
 *
 * In production: this service publishes a message to SQS/Kafka,
 * and transcoding workers (ECS tasks / K8s pods) consume and process.
 */
public class TranscodingService {

    private final TranscodingStrategy transcodingStrategy;

    // Track all transcode jobs per video
    // Key: videoId → list of jobs
    private final Map<String, List<TranscodeJob>> jobsByVideo = new ConcurrentHashMap<>();

    public TranscodingService(TranscodingStrategy transcodingStrategy) {
        this.transcodingStrategy = transcodingStrategy;
    }

    /**
     * Transcode a video into all applicable resolutions.
     * Builds the resolution ladder automatically based on the source resolution.
     */
    public List<TranscodeJob> transcodeVideo(Video video) {
        return transcodeVideo(video, video.getCodec());
    }

    /**
     * Transcode a video with a specific target codec.
     */
    public List<TranscodeJob> transcodeVideo(Video video, Codec targetCodec) {
        if (video.getStatus() != VideoStatus.UPLOADED && video.getStatus() != VideoStatus.FAILED) {
            throw new TranscodingException(
                    "Cannot transcode video " + video.getVideoId()
                            + ": status is " + video.getStatus() + ", expected UPLOADED or FAILED");
        }

        // Build resolution ladder: 240p up to the original resolution
        List<Resolution> targets = buildResolutionLadder(video.getOriginalResolution());

        // Transition to TRANSCODING state
        video.startTranscoding();

        // Delegate to the transcoding strategy (parallel or sequential)
        List<TranscodeJob> jobs = transcodingStrategy.transcode(video, targets, targetCodec);

        // Track jobs
        jobsByVideo.put(video.getVideoId(), jobs);

        // Check if all jobs completed successfully
        boolean allCompleted = jobs.stream()
                .allMatch(j -> j.getStatus() == TranscodeJobStatus.COMPLETED);

        if (allCompleted) {
            video.markReady();
        } else {
            // At least one job failed
            long failedCount = jobs.stream()
                    .filter(j -> j.getStatus() == TranscodeJobStatus.FAILED)
                    .count();
            if (failedCount > 0) {
                System.err.println("WARNING: " + failedCount + " transcode jobs failed for video "
                        + video.getVideoId());
                // Don't mark as failed if some succeeded — partial availability
                // In production: retry failed jobs, alert if all fail
                if (failedCount == jobs.size()) {
                    video.markFailed();
                } else {
                    video.markReady(); // Partial availability: some resolutions are ready
                }
            }
        }

        return jobs;
    }

    /**
     * Build a resolution ladder from 240p up to the source resolution.
     * Never transcode higher than the source (no upscaling).
     */
    public List<Resolution> buildResolutionLadder(Resolution sourceResolution) {
        List<Resolution> ladder = new ArrayList<>();
        for (Resolution res : Resolution.values()) {
            if (res.getHeight() <= sourceResolution.getHeight()) {
                ladder.add(res);
            }
        }
        // Sort by height ascending: [240p, 360p, 480p, 720p, 1080p, ...]
        ladder.sort(Comparator.comparingInt(Resolution::getHeight));
        return ladder;
    }

    /**
     * Get all transcode jobs for a video.
     */
    public List<TranscodeJob> getJobsForVideo(String videoId) {
        return jobsByVideo.getOrDefault(videoId, Collections.emptyList());
    }

    /**
     * Get the transcoding strategy in use (for display/comparison).
     */
    public TranscodingStrategy getStrategy() {
        return transcodingStrategy;
    }
}
