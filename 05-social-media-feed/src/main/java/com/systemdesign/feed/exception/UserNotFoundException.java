package com.systemdesign.feed.exception;

public class UserNotFoundException extends FeedException {

    public UserNotFoundException(String userId) {
        super("User not found: " + userId);
    }
}
