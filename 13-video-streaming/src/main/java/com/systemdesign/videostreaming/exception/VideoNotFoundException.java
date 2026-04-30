package com.systemdesign.videostreaming.exception;

/**
 * Thrown when a requested video does not exist.
 *
 * In production: maps to HTTP 404.
 */
public class VideoNotFoundException extends VideoStreamingException {

    public VideoNotFoundException(String videoId) {
        super("Video not found: " + videoId);
    }
}
