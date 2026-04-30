package com.systemdesign.videostreaming.model;

/**
 * Lifecycle states for a single transcode job.
 *
 * Each job converts one video to one specific resolution + codec pair.
 * A video with 6 target resolutions = 6 transcode jobs.
 *
 * In production:
 *   - QUEUED: job is in SQS/Kafka, waiting for a worker
 *   - PROCESSING: a Kubernetes pod picked it up, FFmpeg is running
 *   - COMPLETED: output stored in S3, manifest updated
 *   - FAILED: worker crashed or FFmpeg errored (e.g., corrupt source)
 */
public enum TranscodeJobStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
