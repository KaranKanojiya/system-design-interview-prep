package com.systemdesign.chat.model;

/**
 * Enumerates the types of messages supported by the chat system.
 */
public enum MessageType {
    TEXT("Text"),
    IMAGE("Image"),
    VIDEO("Video"),
    FILE("File"),
    SYSTEM("System");

    private final String displayName;

    MessageType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
