package com.systemdesign.newsfeed.exception;

/**
 * UserNotFoundException — Thrown when a user ID is not found in the repository.
 */
public class UserNotFoundException extends FeedException {

    public UserNotFoundException(String userId) {
        super("User not found: " + userId);
    }
}
