package com.systemdesign.chat.exception;

public class UserNotFoundException extends ChatException {

    public UserNotFoundException(String userId) {
        super("User not found: " + userId);
    }
}
