package com.systemdesign.videostreaming.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Core domain entity representing a video on the platform.
 *
 * Design decisions:
 *   - Builder pattern: videos have many optional fields (description, thumbnail).
 *     Telescoping constructors would be unreadable with 12+ parameters.
 *   - State machine: status transitions are guarded — you can't start transcoding
 *     a video that hasn't been uploaded yet. This prevents data corruption and
 *     mirrors real-world video pipeline invariants.
 *   - Immutable collections: availableResolutions is returned as unmodifiable
 *     to prevent callers from accidentally mutating shared state.
 *
 * In production: this would be a JPA @Entity with optimistic locking (@Version)
 * to handle concurrent status updates from multiple transcoding workers.
 */
public class Video {

    private final String videoId;
    private final String title;
    private final String description;
    private final String uploaderId;
    private final String uploaderName;
    private VideoStatus status;
    private final int durationSeconds;
    private final long fileSizeBytes;
    private final Resolution originalResolution;
    private final List<Resolution> availableResolutions;
    private final Codec codec;
    private String thumbnailUrl;
    private final LocalDateTime createdAt;
    private LocalDateTime transcodedAt;

    private Video(Builder builder) {
        this.videoId = builder.videoId;
        this.title = builder.title;
        this.description = builder.description;
        this.uploaderId = builder.uploaderId;
        this.uploaderName = builder.uploaderName;
        this.status = builder.status;
        this.durationSeconds = builder.durationSeconds;
        this.fileSizeBytes = builder.fileSizeBytes;
        this.originalResolution = builder.originalResolution;
        this.availableResolutions = new ArrayList<>(builder.availableResolutions);
        this.codec = builder.codec;
        this.thumbnailUrl = builder.thumbnailUrl;
        this.createdAt = builder.createdAt;
        this.transcodedAt = builder.transcodedAt;
    }

    // ─── State Machine Transitions ──────────────────────────────────────
    // Each transition validates the current state before allowing the move.
    // This prevents impossible states like UPLOADING → READY (skipping transcode).

    /**
     * Transition: UPLOADING → UPLOADED
     * Called when all chunks have been received and assembled.
     */
    public void markUploaded() {
        if (status != VideoStatus.UPLOADING) {
            throw new IllegalStateException(
                "Cannot mark as uploaded: current status is " + status + ", expected UPLOADING");
        }
        this.status = VideoStatus.UPLOADED;
    }

    /**
     * Transition: UPLOADED → TRANSCODING (or FAILED → TRANSCODING for retry)
     * Called when the transcoding pipeline picks up this video.
     */
    public void startTranscoding() {
        if (status != VideoStatus.UPLOADED && status != VideoStatus.FAILED) {
            throw new IllegalStateException(
                "Cannot start transcoding: current status is " + status + ", expected UPLOADED or FAILED");
        }
        this.status = VideoStatus.TRANSCODING;
    }

    /**
     * Transition: TRANSCODING → READY
     * Called when ALL resolution variants have been transcoded successfully.
     */
    public void markReady() {
        if (status != VideoStatus.TRANSCODING) {
            throw new IllegalStateException(
                "Cannot mark as ready: current status is " + status + ", expected TRANSCODING");
        }
        this.status = VideoStatus.READY;
        this.transcodedAt = LocalDateTime.now();
    }

    /**
     * Transition: UPLOADING|TRANSCODING → FAILED
     * Called when upload or transcoding encounters an unrecoverable error.
     */
    public void markFailed() {
        if (status != VideoStatus.UPLOADING && status != VideoStatus.TRANSCODING) {
            throw new IllegalStateException(
                "Cannot mark as failed: current status is " + status + ", expected UPLOADING or TRANSCODING");
        }
        this.status = VideoStatus.FAILED;
    }

    /**
     * Transition: Any → DELETED (soft-delete)
     * Allowed from any state — even failed videos need cleanup.
     */
    public void delete() {
        if (status == VideoStatus.DELETED) {
            throw new IllegalStateException("Video is already deleted");
        }
        this.status = VideoStatus.DELETED;
    }

    /**
     * Add a resolution to the available list (called after each transcode job completes).
     */
    public void addAvailableResolution(Resolution resolution) {
        if (!availableResolutions.contains(resolution)) {
            availableResolutions.add(resolution);
        }
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getVideoId() { return videoId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getUploaderId() { return uploaderId; }
    public String getUploaderName() { return uploaderName; }
    public VideoStatus getStatus() { return status; }
    public int getDurationSeconds() { return durationSeconds; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public Resolution getOriginalResolution() { return originalResolution; }
    public Codec getCodec() { return codec; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getTranscodedAt() { return transcodedAt; }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    /**
     * Returns an unmodifiable view to prevent external mutation.
     * Callers must use addAvailableResolution() to modify the list.
     */
    public List<Resolution> getAvailableResolutions() {
        return Collections.unmodifiableList(availableResolutions);
    }

    @Override
    public String toString() {
        return "Video{id='" + videoId + "', title='" + title + "', status=" + status
                + ", resolution=" + originalResolution + ", codec=" + codec + "}";
    }

    // ─── Builder ────────────────────────────────────────────────────────

    public static class Builder {
        private String videoId;
        private String title;
        private String description = "";
        private String uploaderId;
        private String uploaderName;
        private VideoStatus status = VideoStatus.UPLOADING;
        private int durationSeconds;
        private long fileSizeBytes;
        private Resolution originalResolution;
        private List<Resolution> availableResolutions = new ArrayList<>();
        private Codec codec = Codec.H264;
        private String thumbnailUrl;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime transcodedAt;

        public Builder(String videoId, String title, String uploaderId) {
            this.videoId = videoId;
            this.title = title;
            this.uploaderId = uploaderId;
        }

        public Builder description(String val) { description = val; return this; }
        public Builder uploaderName(String val) { uploaderName = val; return this; }
        public Builder status(VideoStatus val) { status = val; return this; }
        public Builder durationSeconds(int val) { durationSeconds = val; return this; }
        public Builder fileSizeBytes(long val) { fileSizeBytes = val; return this; }
        public Builder originalResolution(Resolution val) { originalResolution = val; return this; }
        public Builder availableResolutions(List<Resolution> val) { availableResolutions = new ArrayList<>(val); return this; }
        public Builder codec(Codec val) { codec = val; return this; }
        public Builder thumbnailUrl(String val) { thumbnailUrl = val; return this; }
        public Builder createdAt(LocalDateTime val) { createdAt = val; return this; }
        public Builder transcodedAt(LocalDateTime val) { transcodedAt = val; return this; }

        public Video build() {
            if (videoId == null || title == null || uploaderId == null) {
                throw new IllegalArgumentException("videoId, title, and uploaderId are required");
            }
            return new Video(this);
        }
    }
}
