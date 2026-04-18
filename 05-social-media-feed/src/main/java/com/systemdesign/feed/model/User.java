package com.systemdesign.feed.model;

import java.time.LocalDateTime;

public class User {

    public static int CELEBRITY_THRESHOLD = 10_000;

    private final String userId;
    private final String username;
    private String displayName;
    private String bio;
    private int followerCount;
    private int followingCount;
    private UserType userType;
    private final LocalDateTime createdAt;

    public User(String userId, String username, String displayName, String bio,
                int followerCount, int followingCount, UserType userType) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.bio = bio;
        this.followerCount = followerCount;
        this.followingCount = followingCount;
        this.userType = userType;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isCelebrity() {
        return followerCount >= CELEBRITY_THRESHOLD;
    }

    public synchronized void incrementFollowers() {
        followerCount++;
        updateUserType();
    }

    public synchronized void decrementFollowers() {
        if (followerCount > 0) {
            followerCount--;
        }
        updateUserType();
    }

    public synchronized void incrementFollowing() {
        followingCount++;
    }

    public synchronized void decrementFollowing() {
        if (followingCount > 0) {
            followingCount--;
        }
    }

    private void updateUserType() {
        if (followerCount >= CELEBRITY_THRESHOLD && userType == UserType.NORMAL) {
            userType = UserType.CELEBRITY;
            System.out.println("  *** @" + username + " crossed " + CELEBRITY_THRESHOLD
                    + " followers! UserType changed: NORMAL -> CELEBRITY ***");
        } else if (followerCount < CELEBRITY_THRESHOLD && userType == UserType.CELEBRITY) {
            userType = UserType.NORMAL;
            System.out.println("  *** @" + username + " dropped below " + CELEBRITY_THRESHOLD
                    + " followers! UserType changed: CELEBRITY -> NORMAL ***");
        }
    }

    // Getters
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getBio() { return bio; }
    public int getFollowerCount() { return followerCount; }
    public int getFollowingCount() { return followingCount; }
    public UserType getUserType() { return userType; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // Setters for mutable fields
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setBio(String bio) { this.bio = bio; }
    public void setFollowerCount(int followerCount) {
        this.followerCount = followerCount;
        updateUserType();
    }

    @Override
    public String toString() {
        String prefix = isCelebrity() ? "[Celebrity]" : "[Normal]";
        return prefix + " @" + username + " (" + displayName + ") — "
                + String.format("%,d", followerCount) + " followers, "
                + followingCount + " following";
    }
}
