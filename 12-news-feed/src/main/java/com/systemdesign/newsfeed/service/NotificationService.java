package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.model.Post;

import java.util.List;

/**
 * NotificationService — Sends notifications for feed events.
 *
 * Design notes for interview:
 * - In production, notifications would be:
 *   1. Push notifications (APNs for iOS, FCM for Android)
 *   2. In-app notifications (WebSocket or SSE)
 *   3. Email digests (batch job)
 * - Notifications are async — they should never block the main request path.
 *   Typically implemented via a message queue (Kafka/SQS) with notification workers.
 * - Rate limiting is critical: don't notify a user 1000 times because 1000
 *   people liked their post. Batch/aggregate instead.
 *
 * Here we simulate with console output.
 */
public class NotificationService {

    /**
     * Notify followers about a new post.
     * In production: this would be batched and sent via push notification service.
     */
    public void notifyNewPost(List<String> followerIds, Post post) {
        // In production, we would NOT notify ALL followers immediately.
        // Instead, we'd use a priority queue: notify active users first,
        // batch notifications for inactive users into a digest.
        System.out.printf("   [Notification] New post by '%s' — notifying %d followers%n",
                post.getAuthorName(), followerIds.size());
    }

    /**
     * Notify post author that someone liked their post.
     */
    public void notifyLike(String postAuthorId, String likerName) {
        System.out.printf("   [Notification] -> %s: '%s' liked your post%n",
                postAuthorId, likerName);
    }

    /**
     * Notify post author that someone commented on their post.
     */
    public void notifyComment(String postAuthorId, String commenterName) {
        System.out.printf("   [Notification] -> %s: '%s' commented on your post%n",
                postAuthorId, commenterName);
    }

    /**
     * Notify post author that someone shared their post.
     */
    public void notifyShare(String postAuthorId, String sharerName) {
        System.out.printf("   [Notification] -> %s: '%s' shared your post%n",
                postAuthorId, sharerName);
    }
}
