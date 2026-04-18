package com.systemdesign.chat.model;

/**
 * Represents a user's presence status with a visual indicator.
 */
public enum UserStatus {
    ONLINE("●"),
    OFFLINE("○"),
    AWAY("◑");

    private final String indicator;

    UserStatus(String indicator) {
        this.indicator = indicator;
    }

    public String getIndicator() {
        return indicator;
    }
}
