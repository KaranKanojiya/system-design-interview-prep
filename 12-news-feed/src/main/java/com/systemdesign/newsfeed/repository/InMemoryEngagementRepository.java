package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.Comment;
import com.systemdesign.newsfeed.model.Like;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryEngagementRepository — ConcurrentHashMap-backed engagement storage.
 *
 * Uses composite keys for duplicate prevention (one like per user per post).
 * Maintains indexes for efficient queries by postId and userId.
 */
public class InMemoryEngagementRepository implements EngagementRepository {

    // --- Likes ---
    // Key: "userId:postId" to prevent duplicates
    private final Map<String, Like> likes = new ConcurrentHashMap<>();
    // Index: postId -> set of userIds who liked it
    private final Map<String, Set<String>> likesByPost = new ConcurrentHashMap<>();
    // Index: userId -> set of postIds they liked
    private final Map<String, Set<String>> likesByUser = new ConcurrentHashMap<>();

    // --- Comments ---
    // Key: commentId
    private final Map<String, Comment> comments = new ConcurrentHashMap<>();
    // Index: postId -> list of commentIds
    private final Map<String, List<String>> commentsByPost = new ConcurrentHashMap<>();

    // === Likes ===

    @Override
    public void saveLike(Like like) {
        String key = like.getUserId() + ":" + like.getPostId();
        likes.put(key, like);

        likesByPost
                .computeIfAbsent(like.getPostId(), k -> ConcurrentHashMap.newKeySet())
                .add(like.getUserId());
        likesByUser
                .computeIfAbsent(like.getUserId(), k -> ConcurrentHashMap.newKeySet())
                .add(like.getPostId());
    }

    @Override
    public boolean hasLiked(String userId, String postId) {
        return likes.containsKey(userId + ":" + postId);
    }

    @Override
    public List<Like> findLikesByPostId(String postId) {
        Set<String> userIds = likesByPost.get(postId);
        if (userIds == null) return new ArrayList<>();
        return userIds.stream()
                .map(uid -> likes.get(uid + ":" + postId))
                .filter(l -> l != null)
                .collect(Collectors.toList());
    }

    @Override
    public List<Like> findLikesByUserId(String userId) {
        Set<String> postIds = likesByUser.get(userId);
        if (postIds == null) return new ArrayList<>();
        return postIds.stream()
                .map(pid -> likes.get(userId + ":" + pid))
                .filter(l -> l != null)
                .collect(Collectors.toList());
    }

    @Override
    public int countLikesByPostId(String postId) {
        Set<String> userIds = likesByPost.get(postId);
        return userIds == null ? 0 : userIds.size();
    }

    // === Comments ===

    @Override
    public void saveComment(Comment comment) {
        comments.put(comment.getCommentId(), comment);
        commentsByPost
                .computeIfAbsent(comment.getPostId(), k -> new ArrayList<>())
                .add(comment.getCommentId());
    }

    @Override
    public List<Comment> findCommentsByPostId(String postId) {
        List<String> commentIds = commentsByPost.get(postId);
        if (commentIds == null) return new ArrayList<>();
        return commentIds.stream()
                .map(comments::get)
                .filter(c -> c != null)
                .collect(Collectors.toList());
    }

    @Override
    public int countCommentsByPostId(String postId) {
        List<String> commentIds = commentsByPost.get(postId);
        return commentIds == null ? 0 : commentIds.size();
    }

    @Override
    public long totalLikes() {
        return likes.size();
    }

    @Override
    public long totalComments() {
        return comments.size();
    }
}
