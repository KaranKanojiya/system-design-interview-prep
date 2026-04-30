package com.systemdesign.videostreaming.model;

/**
 * Video lifecycle states — forms a state machine with guarded transitions.
 *
 * State transitions:
 *   UPLOADING → UPLOADED → TRANSCODING → READY
 *                                      → FAILED (retry possible)
 *   Any state → DELETED (soft-delete)
 *
 * Why an enum and not a String?
 *   - Compile-time safety: can't misspell "TRANCODING"
 *   - State machine logic lives in Video.java, but valid states are bounded here
 *   - In production: this might map to a DB column via JPA @Enumerated
 */
public enum VideoStatus {

    /** Initial state: chunks are still being uploaded. */
    UPLOADING,

    /** All chunks received, file assembled on storage. Ready for transcoding. */
    UPLOADED,

    /** Transcoding pipeline is converting to multiple resolutions/codecs. */
    TRANSCODING,

    /** All transcoding jobs completed. Video is playable. */
    READY,

    /** Transcoding or upload failed. May be retried. */
    FAILED,

    /** Soft-deleted. Chunks may still exist in cold storage for legal holds. */
    DELETED
}
