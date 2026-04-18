package com.systemdesign.notification.model;

/**
 * Supported notification delivery channels.
 */
public enum Channel {
    PUSH("Push Notification"),
    EMAIL("Email"),
    SMS("SMS"),
    IN_APP("In-App");

    private final String displayName;

    Channel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
