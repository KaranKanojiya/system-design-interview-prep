package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.Comment;
import com.systemdesign.newsfeed.model.Like;

import java.util.List;

/**
 * EngagementRepository — Data access interface for Likes and Comments.
 *
 * In production: likes would be in Redis (SET for membership check, counter for count).
 * Comments would be in Cassandra/DynamoDB partitioned by postId for efficient retrieval.
 */
public interface EngagementRepository {

    // --- Likes ---
    void saveLike(Like like);

    boolean hasLiked(String userId, String postId);

    List<Like> findLikesByPostId(String postId);

    /**
     * Find all likes by a specific user. Used for affinity calculation
     * (which authors does this user frequently interact with?).
     */
    List<Like> findLikesByUserId(String userId);

    int countLikesByPostId(String postId);

    // --- Comments ---
    void saveComment(Comment comment);

    List<Comment> findCommentsByPostId(String postId);

    int countCommentsByPostId(String postId);

    long totalLikes();

    long totalComments();
}
