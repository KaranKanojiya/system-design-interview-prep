package com.systemdesign.newsfeed.exception;

/**
 * PostNotFoundException — Thrown when a post ID is not found in the repository.
 */
public class PostNotFoundException extends FeedException {

    public PostNotFoundException(String postId) {
        super("Post not found: " + postId);
    }
}
