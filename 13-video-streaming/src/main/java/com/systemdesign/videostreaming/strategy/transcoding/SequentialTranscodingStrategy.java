package com.systemdesign.videostreaming.strategy.transcoding;

import com.systemdesign.videostreaming.model.Codec;
import com.systemdesign.videostreaming.model.Resolution;
import com.systemdesign.videostreaming.model.TranscodeJob;
import com.systemdesign.videostreaming.model.Video;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sequential transcoding: resolutions are transcoded one at a time.
 *
 * This is the ANTI-PATTERN comparison — included to demonstrate why
 * parallel transcoding is essential for a video platform.
 *
 * Performance comparison (6 target resolutions):
 *   - Sequential: sum of all encoding times (e.g., 50+70+100+150+200+400 = 970ms)
 *   - Parallel:   max of all encoding times (e.g., max = 400ms)
 *   - Speedup:    ~2.4x for 6 resolutions (limited by the slowest job)
 *
 * When would sequential actually be used?
 *   - Small platforms with limited compute budget
 *   - Priority encoding: encode 360p first (for fast initial availability),
 *     then progressively encode higher resolutions
 *   - Debugging: easier to trace issues when jobs run one at a time
 */
public class SequentialTranscodingStrategy implements TranscodingStrategy {

    @Override
    public List<TranscodeJob> transcode(Video video, List<Resolution> targets, Codec codec) {
        List<TranscodeJob> jobs = new ArrayList<>();

        // Process each resolution one at a time — no concurrency
        for (Resolution target : targets) {
            TranscodeJob job = new TranscodeJob(
                    UUID.randomUUID().toString(),
                    video.getVideoId(),
                    target,
                    codec
            );

            try {
                job.startProcessing();

                // Same simulation as parallel, but runs sequentially
                // Total time = SUM of all jobs (not MAX)
                int simulatedTimeMs = target.getHeight() / 5;

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

            jobs.add(job);
        }

        return jobs;
    }
}
