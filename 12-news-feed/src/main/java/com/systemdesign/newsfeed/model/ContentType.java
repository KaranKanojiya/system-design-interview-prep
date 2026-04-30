package com.systemdesign.newsfeed.model;

/**
 * ContentType — Enum representing post content types with ranking weights.
 *
 * Design notes for interview:
 * - Each content type has a weight used in the algorithmic ranking formula.
 * - VIDEO (1.5) ranks highest because platforms optimize for watch-time.
 * - POLL (1.4) ranks high because it drives engagement (clicks/votes).
 * - IMAGE (1.3) outranks TEXT (1.0) because visual content gets more engagement.
 * - LINK (1.1) gets a small boost for off-platform content (drives less engagement).
 *
 * In production, these weights would be tuned by ML models, not hardcoded.
 * But the concept of content-type boosting is real — Facebook/Instagram/LinkedIn
 * all weight video content higher in their ranking algorithms.
 */
public enum ContentType {

    TEXT(1.0),
    IMAGE(1.3),
    VIDEO(1.5),
    LINK(1.1),
    POLL(1.4);

    // --- Weight used in AlgorithmicRankingStrategy ---
    // Multiplied into the final score: score = affinity * recency * engagement * weight
    private final double weight;

    ContentType(double weight) {
        this.weight = weight;
    }

    public double getWeight() {
        return weight;
    }
}
