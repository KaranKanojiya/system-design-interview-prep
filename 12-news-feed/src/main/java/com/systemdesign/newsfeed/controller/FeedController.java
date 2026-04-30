package com.systemdesign.newsfeed.controller;

import com.systemdesign.newsfeed.exception.FeedException;
import com.systemdesign.newsfeed.model.ContentType;
import com.systemdesign.newsfeed.model.FeedCursor;
import com.systemdesign.newsfeed.model.FeedItem;
import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.service.EngagementService;
import com.systemdesign.newsfeed.service.FeedService;
import com.systemdesign.newsfeed.service.PostService;
import com.systemdesign.newsfeed.service.SocialGraphService;

import java.util.List;

/**
 * FeedController — Simulated REST controller for the news feed API.
 *
 * Design notes for interview:
 * - In production, this would be a Spring MVC / JAX-RS controller with HTTP endpoints.
 * - Each method simulates a REST endpoint:
 *   POST /posts          -> handleCreatePost
 *   GET  /feed/{userId}  -> handleGetFeed
 *   POST /posts/{id}/like -> handleLike
 *   POST /posts/{id}/comment -> handleComment
 *   POST /users/{id}/follow  -> handleFollow
 *   DELETE /users/{id}/follow -> handleUnfollow
 *
 * - Error handling wraps service calls with try-catch for clean error messages.
 */
public class FeedController {

    private final PostService postService;
    private final FeedService feedService;
    private final EngagementService engagementService;
    private final SocialGraphService socialGraphService;

    public FeedController(PostService postService,
                          FeedService feedService,
                          EngagementService engagementService,
                          SocialGraphService socialGraphService) {
        this.postService = postService;
        this.feedService = feedService;
        this.engagementService = engagementService;
        this.socialGraphService = socialGraphService;
    }

    /**
     * POST /posts — Create a new post.
     * Triggers fan-out to followers.
     */
    public Post handleCreatePost(String userId, String content, ContentType contentType, String mediaUrl) {
        try {
            return postService.createPost(userId, content, contentType, mediaUrl);
        } catch (FeedException e) {
            System.out.println("   [Controller] Error creating post: " + e.getMessage());
            return null;
        }
    }

    /**
     * GET /feed/{userId} — Get a user's feed with cursor pagination.
     */
    public List<FeedItem> handleGetFeed(String userId, FeedCursor cursor) {
        try {
            return feedService.getFeed(userId, cursor);
        } catch (FeedException e) {
            System.out.println("   [Controller] Error getting feed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * POST /posts/{postId}/like — Like a post.
     */
    public void handleLike(String userId, String postId) {
        try {
            engagementService.likePost(userId, postId);
        } catch (FeedException e) {
            System.out.println("   [Controller] Error liking post: " + e.getMessage());
        }
    }

    /**
     * POST /posts/{postId}/comment — Comment on a post.
     */
    public void handleComment(String userId, String postId, String content) {
        try {
            engagementService.commentOnPost(userId, postId, content);
        } catch (FeedException e) {
            System.out.println("   [Controller] Error commenting: " + e.getMessage());
        }
    }

    /**
     * POST /posts/{postId}/share — Share a post.
     */
    public void handleShare(String userId, String postId) {
        try {
            engagementService.sharePost(userId, postId);
        } catch (FeedException e) {
            System.out.println("   [Controller] Error sharing post: " + e.getMessage());
        }
    }

    /**
     * POST /users/{targetId}/follow — Follow a user.
     */
    public void handleFollow(String userId, String targetId) {
        try {
            socialGraphService.follow(userId, targetId);
        } catch (FeedException e) {
            System.out.println("   [Controller] Error following: " + e.getMessage());
        }
    }

    /**
     * DELETE /users/{targetId}/follow — Unfollow a user.
     */
    public void handleUnfollow(String userId, String targetId) {
        try {
            socialGraphService.unfollow(userId, targetId);
        } catch (FeedException e) {
            System.out.println("   [Controller] Error unfollowing: " + e.getMessage());
        }
    }
}
