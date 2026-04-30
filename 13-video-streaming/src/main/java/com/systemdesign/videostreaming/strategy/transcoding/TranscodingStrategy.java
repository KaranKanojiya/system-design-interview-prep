package com.systemdesign.videostreaming.strategy.transcoding;

import com.systemdesign.videostreaming.model.Codec;
import com.systemdesign.videostreaming.model.Resolution;
import com.systemdesign.videostreaming.model.TranscodeJob;
import com.systemdesign.videostreaming.model.Video;

import java.util.List;

/**
 * Strategy interface for transcoding a video into multiple resolutions.
 *
 * Why Strategy pattern?
 *   - Different transcoding approaches have vastly different performance:
 *     - ParallelTranscodingStrategy: fast, uses N workers concurrently
 *     - SequentialTranscodingStrategy: slow, one at a time (anti-pattern comparison)
 *   - The caller (TranscodingService) doesn't need to know which approach is used
 *   - Easy to swap at configuration time (AppConfig)
 *
 * In production: the strategy would submit jobs to a distributed queue (SQS/Kafka)
 * rather than running them in-process.
 */
public interface TranscodingStrategy {

    /**
     * Transcode a video into the given target resolutions using the specified codec.
     *
     * @param video   the source video to transcode
     * @param targets list of target resolutions (e.g., [240p, 360p, 720p, 1080p])
     * @param codec   the target codec (e.g., H264, H265)
     * @return list of TranscodeJob results (one per target resolution)
     */
    List<TranscodeJob> transcode(Video video, List<Resolution> targets, Codec codec);
}
