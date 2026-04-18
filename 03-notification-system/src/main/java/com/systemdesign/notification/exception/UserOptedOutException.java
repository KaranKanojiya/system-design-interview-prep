package com.systemdesign.notification.exception;

import com.systemdesign.notification.model.Channel;

/**
 * Thrown when a notification cannot be sent because the user has
 * opted out of the target channel or is in quiet hours.
 */
public class UserOptedOutException extends NotificationException {

    private final String userId;
    private final Channel channel;

    public UserOptedOutException(String userId, Channel channel) {
        super(String.format("User '%s' has opted out of %s notifications", userId, channel.getDisplayName()));
        this.userId = userId;
        this.channel = channel;
    }

    public String getUserId() { return userId; }
    public Channel getChannel() { return channel; }
}
