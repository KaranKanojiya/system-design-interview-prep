package com.systemdesign.newsfeed.model;

import java.time.LocalDateTime;

/**
 * Comment — Represents a comment on a post.
 *
 * Design notes for interview:
 * - Comments are stored separately from posts (not embedded) because:
 *   1. A post can have millions of comments (think viral posts).
 *   2. Comments need their own pagination (infinite scroll on comments).
 *   3. Comments have their own engagement (likes on comments, replies).
 * - In production, comments would be in a separate table/collection,
 *   likely partitioned by postId for efficient retrieval.
 */
public class Comment {

    private final String commentId;
    private final String postId;
    private final String authorId;
    private final String authorName;
    private final String content;
    private final LocalDateTime createdAt;

    public Comment(String commentId, String postId, String authorId,
                   String authorName, String content) {
        this.commentId = commentId;
        this.postId = postId;
        this.authorId = authorId;
        this.authorName = authorName;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    public String getCommentId() {
        return commentId;
    }

    public String getPostId() {
        return postId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return String.format("Comment{id='%s', post='%s', author='%s', content='%s'}",
                commentId, postId, authorName, content);
    }
}
