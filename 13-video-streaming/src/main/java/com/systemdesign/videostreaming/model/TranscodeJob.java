package com.systemdesign.videostreaming.model;

import java.time.LocalDateTime;

/**
 * Represents a single transcoding job: converting one video to one resolution + codec.
 *
 * A video needing 6 resolutions generates 6 TranscodeJobs.
 * In parallel transcoding, all 6 run concurrently on separate workers.
 *
 * In production:
 *   - Each job maps to a Kubernetes pod running FFmpeg
 *   - Jobs are submitted to a message queue (SQS/Kafka)
 *   - Progress is updated via heartbeat from the worker
 *   - Failed jobs are retried with exponential backoff
 *   - Dead letter queue for jobs that fail 3+ times
 */
public class TranscodeJob {

    private final String jobId;
    private final String videoId;
    private final Resolution targetResolution;
    private final Codec targetCodec;
    private TranscodeJobStatus status;
    private int progressPercent;
    private final LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;

    public TranscodeJob(String jobId, String videoId, Resolution targetResolution, Codec targetCodec) {
        this.jobId = jobId;
        this.videoId = videoId;
        this.targetResolution = targetResolution;
        this.targetCodec = targetCodec;
        this.status = TranscodeJobStatus.QUEUED;
        this.progressPercent = 0;
        this.startedAt = LocalDateTime.now();
    }

    // ─── State Transitions ──────────────────────────────────────────────

    public void startProcessing() {
        if (status != TranscodeJobStatus.QUEUED) {
            throw new IllegalStateException("Job " + jobId + " is not QUEUED, cannot start processing");
        }
        this.status = TranscodeJobStatus.PROCESSING;
    }

    public void updateProgress(int percent) {
        if (status != TranscodeJobStatus.PROCESSING) {
            throw new IllegalStateException("Job " + jobId + " is not PROCESSING, cannot update progress");
        }
        this.progressPercent = Math.min(100, Math.max(0, percent));
    }

    public void markCompleted() {
        if (status != TranscodeJobStatus.PROCESSING) {
            throw new IllegalStateException("Job " + jobId + " is not PROCESSING, cannot mark completed");
        }
        this.status = TranscodeJobStatus.COMPLETED;
        this.progressPercent = 100;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.status = TranscodeJobStatus.FAILED;
        this.errorMessage = error;
        this.completedAt = LocalDateTime.now();
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getJobId() { return jobId; }
    public String getVideoId() { return videoId; }
    public Resolution getTargetResolution() { return targetResolution; }
    public Codec getTargetCodec() { return targetCodec; }
    public TranscodeJobStatus getStatus() { return status; }
    public int getProgressPercent() { return progressPercent; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getErrorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return "TranscodeJob{id='" + jobId + "', video='" + videoId
                + "', target=" + targetResolution.getLabel()
                + ", codec=" + targetCodec.name()
                + ", status=" + status + ", progress=" + progressPercent + "%}";
    }
}
