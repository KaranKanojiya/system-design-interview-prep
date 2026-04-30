package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.exception.PostNotFoundException;
import com.systemdesign.newsfeed.exception.UserNotFoundException;
import com.systemdesign.newsfeed.model.Comment;
import com.systemdesign.newsfeed.model.Like;
import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.EngagementRepository;
import com.systemdesign.newsfeed.repository.PostRepository;
import com.systemdesign.newsfeed.repository.UserRepository;
import com.systemdesign.newsfeed.strategy.ranking.AlgorithmicRankingStrategy;

import java.util.UUID;

/**
 * EngagementService — Handles likes, comments, and shares on posts.
 *
 * Design notes for interview:
 * - Each engagement action does THREE things:
 *   1. Persist the engagement record (Like, Comment).
 *   2. Increment the counter on the Post (denormalized for fast reads).
 *   3. Record the interaction in AlgorithmicRankingStrategy (affinity tracking).
 *   4. Send a notification to the post author.
 *
 * - In production, engagement writes would be:
 *   1. Sync: save to engagement store (Redis for likes, Cassandra for comments).
 *   2. Async: increment counters via message queue (avoid counter contention).
 *   3. Async: update affinity scores in the ML feature store.
 *   4. Async: send notification via push notification service.
 *
 * Call chain:
 *   FeedController.handleLike()
 *     -> EngagementService.likePost()
 *       -> EngagementRepository.saveLike()
 *       -> Post.incrementLikes()
 *       -> AlgorithmicRankingStrategy.recordInteraction()
 *       -> NotificationService.notifyLike()
 */
public class EngagementService {

    private final EngagementRepository engagementRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final AlgorithmicRankingStrategy algorithmicRanking;
    private final NotificationService notificationService;

    public EngagementService(EngagementRepository engagementRepository,
                             PostRepository postRepository,
                             UserRepository userRepository,
                             AlgorithmicRankingStrategy algorithmicRanking,
                             NotificationService notificationService) {
        this.engagementRepository = engagementRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.algorithmicRanking = algorithmicRanking;
        this.notificationService = notificationService;
    }

    /**
     * Like a post. Prevents duplicate likes.
     */
    public void likePost(String userId, String postId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException(postId));

        // Prevent duplicate likes
        if (engagementRepository.hasLiked(userId, postId)) {
            System.out.printf("   [Engagement] %s already liked post '%s' — skipping%n",
                    user.getName(), postId);
            return;
        }

        // 1. Save like record
        Like like = new Like(userId, postId);
        engagementRepository.saveLike(like);

        // 2. Increment counter on post (thread-safe via AtomicInteger)
        post.incrementLikes();

        // 3. Record interaction for affinity scoring
        // This means: "userId interacted with post's author"
        // Next time we rank userId's feed, posts by this author get boosted.
        algorithmicRanking.recordInteraction(userId, post.getAuthorId());

        // 4. Notify post author
        if (!userId.equals(post.getAuthorId())) {
            notificationService.notifyLike(post.getAuthorId(), user.getName());
        }

        System.out.printf("   [Engagement] %s liked post '%s' (total likes: %d)%n",
                user.getName(), postId, post.getLikeCount());
    }

    /**
     * Comment on a post.
     */
    public Comment commentOnPost(String userId, String postId, String content) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException(postId));

        // 1. Save comment
        Comment comment = new Comment(
                UUID.randomUUID().toString().substring(0, 8),
                postId, userId, user.getName(), content
        );
        engagementRepository.saveComment(comment);

        // 2. Increment counter on post
        post.incrementComments();

        // 3. Record interaction for affinity scoring
        algorithmicRanking.recordInteraction(userId, post.getAuthorId());

        // 4. Notify post author
        if (!userId.equals(post.getAuthorId())) {
            notificationService.notifyComment(post.getAuthorId(), user.getName());
        }

        System.out.printf("   [Engagement] %s commented on post '%s': '%s' (total comments: %d)%n",
                user.getName(), postId, content, post.getCommentCount());

        return comment;
    }

    /**
     * Share a post. Increments share counter and records interaction.
     */
    public void sharePost(String userId, String postId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException(postId));

        // 1. Increment share counter
        post.incrementShares();

        // 2. Record interaction for affinity scoring (strongest signal)
        algorithmicRanking.recordInteraction(userId, post.getAuthorId());

        // 3. Notify post author
        if (!userId.equals(post.getAuthorId())) {
            notificationService.notifyShare(post.getAuthorId(), user.getName());
        }

        System.out.printf("   [Engagement] %s shared post '%s' (total shares: %d)%n",
                user.getName(), postId, post.getShareCount());
    }
}
