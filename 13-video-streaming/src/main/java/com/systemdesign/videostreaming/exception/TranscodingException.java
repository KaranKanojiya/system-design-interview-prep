package com.systemdesign.videostreaming.exception;

/**
 * Thrown when a transcoding operation fails.
 *
 * Scenarios: corrupt source video, unsupported codec, worker timeout.
 * In production: the failed job goes to a dead-letter queue after N retries.
 */
public class TranscodingException extends VideoStreamingException {

    public TranscodingException(String message) {
        super(message);
    }

    public TranscodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
