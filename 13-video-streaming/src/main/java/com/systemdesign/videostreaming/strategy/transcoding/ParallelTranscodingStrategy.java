package com.systemdesign.videostreaming.strategy.transcoding;

import com.systemdesign.videostreaming.model.Codec;
import com.systemdesign.videostreaming.model.Resolution;
import com.systemdesign.videostreaming.model.TranscodeJob;
import com.systemdesign.videostreaming.model.Video;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Parallel transcoding: all target resolutions are transcoded concurrently.
 *
 * How it works:
 *   1. For each target resolution, create a TranscodeJob
 *   2. Submit all jobs to an ExecutorService (thread pool)
 *   3. Each job simulates transcoding (sleep proportional to resolution)
 *   4. Wait for all jobs to complete (Future.get())
 *   5. Return the completed jobs
 *
 * Why parallel?
 *   - A 1080p video with 6 target resolutions takes ~6x longer sequentially
 *   - Parallel transcoding finishes in the time of the SLOWEST resolution (4K)
 *   - Real speedup: 6x or more (limited by CPU cores, not resolution count)
 *
 * Production: Kubernetes pods, each transcode job = separate container.
 * AWS MediaConvert or FFmpeg in Docker containers managed by ECS/EKS.
 * Each pod has dedicated GPU (for hardware-accelerated encoding) or CPU cores.
 */
public class ParallelTranscodingStrategy implements TranscodingStrategy {

    private final ExecutorService executorService;

    public ParallelTranscodingStrategy(int threadPoolSize) {
        // Fixed thread pool simulates a pool of encoding workers
        // In production: this is a Kubernetes cluster with auto-scaling
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);
    }

    @Override
    public List<TranscodeJob> transcode(Video video, List<Resolution> targets, Codec codec) {
        List<TranscodeJob> jobs = new ArrayList<>();
        List<Future<TranscodeJob>> futures = new ArrayList<>();

        // Step 1: Submit all transcode jobs concurrently
        // Each job runs on a separate thread (simulating a separate worker/pod)
        for (Resolution target : targets) {
            TranscodeJob job = new TranscodeJob(
                    UUID.randomUUID().toString(),
                    video.getVideoId(),
                    target,
                    codec
            );
            jobs.add(job);

            Future<TranscodeJob> future = executorService.submit(() -> {
                try {
                    job.startProcessing();

                    // Simulate transcoding time proportional to resolution
                    // Higher resolution = more pixels to encode = more time
                    // 240p → ~50ms, 4K → ~400ms (scaled down for demo)
                    int simulatedTimeMs = target.getHeight() / 5;

                    // Simulate progress updates (25%, 50%, 75%, 100%)
                    for (int progress = 25; progress <= 75; progress += 25) {
                        Thread.sleep(simulatedTimeMs / 4);
                        job.updateProgress(progress);
                    }
                    Thread.sleep(simulatedTimeMs / 4);

                    job.markCompleted();
                    video.addAvailableResolution(target);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    job.markFailed("Transcoding interrupted: " + e.getMessage());
                } catch (Exception e) {
                    job.markFailed("Transcoding failed: " + e.getMessage());
                }
                return job;
            });
            futures.add(future);
        }

        // Step 2: Wait for ALL jobs to complete before returning
        // This is a barrier — we don't return partial results
        for (Future<TranscodeJob> future : futures) {
            try {
                future.get(); // Blocks until this specific job completes
            } catch (Exception e) {
                // Job failure is already captured in the TranscodeJob status
                System.err.println("Error waiting for transcode job: " + e.getMessage());
            }
        }

        return jobs;
    }

    /**
     * Shutdown the thread pool. Call this when the application exits.
     * In production: Kubernetes handles pod lifecycle, no explicit shutdown needed.
     */
    public void shutdown() {
        executorService.shutdown();
    }
}
