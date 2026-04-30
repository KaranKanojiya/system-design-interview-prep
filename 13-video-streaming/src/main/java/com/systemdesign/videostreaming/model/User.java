package com.systemdesign.videostreaming.model;

/**
 * A platform user — can be a viewer, uploader, or both.
 *
 * In production: this would pull from an auth service (OAuth2 / JWT).
 * Subscriber count and watch time would be aggregated metrics
 * computed by a batch pipeline (Spark), not stored directly.
 */
public class User {

    private final String userId;
    private final String name;
    private final String email;
    private long subscriberCount;
    private int uploadedVideoCount;
    private long totalWatchTimeMinutes;

    public User(String userId, String name, String email) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.subscriberCount = 0;
        this.uploadedVideoCount = 0;
        this.totalWatchTimeMinutes = 0;
    }

    public void incrementUploads() { uploadedVideoCount++; }
    public void addWatchTime(long minutes) { totalWatchTimeMinutes += minutes; }
    public void addSubscriber() { subscriberCount++; }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public long getSubscriberCount() { return subscriberCount; }
    public int getUploadedVideoCount() { return uploadedVideoCount; }
    public long getTotalWatchTimeMinutes() { return totalWatchTimeMinutes; }

    @Override
    public String toString() {
        return "User{id='" + userId + "', name='" + name + "', uploads=" + uploadedVideoCount
                + ", watchTime=" + totalWatchTimeMinutes + "min}";
    }
}
