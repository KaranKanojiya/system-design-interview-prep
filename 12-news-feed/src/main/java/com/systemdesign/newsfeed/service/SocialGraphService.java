package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.exception.UserNotFoundException;
import com.systemdesign.newsfeed.model.Follow;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.FollowRepository;
import com.systemdesign.newsfeed.repository.UserRepository;

import java.util.List;

/**
 * SocialGraphService — Manages the follow/unfollow social graph.
 *
 * Design notes for interview:
 * - The social graph is the foundation of a news feed system.
 *   Without it, we don't know whose posts to show in a user's feed.
 * - Two key operations:
 *   1. follow(A, B): A follows B. B's posts should appear in A's feed.
 *   2. unfollow(A, B): A unfollows B. B's posts should stop appearing.
 * - Denormalized counters (followerCount, followingCount) are kept in sync
 *   on the User object. In production, these would be atomic counters in
 *   Redis or Cassandra to avoid expensive COUNT queries.
 *
 * Call chain:
 *   FeedController.handleFollow() -> SocialGraphService.follow()
 *   FanoutService.distribute() -> SocialGraphService.getFollowers()
 *   FeedService.getFeed() -> SocialGraphService.getFollowing() (for celebrity pull)
 */
public class SocialGraphService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;

    public SocialGraphService(FollowRepository followRepository, UserRepository userRepository) {
        this.followRepository = followRepository;
        this.userRepository = userRepository;
    }

    /**
     * User A follows User B.
     * Creates a directed edge in the social graph.
     * Updates denormalized counters on both users.
     */
    public void follow(String followerId, String followeeId) {
        // Validate both users exist
        User follower = userRepository.findById(followerId)
                .orElseThrow(() -> new UserNotFoundException(followerId));
        User followee = userRepository.findById(followeeId)
                .orElseThrow(() -> new UserNotFoundException(followeeId));

        // Prevent duplicate follows
        if (followRepository.exists(followerId, followeeId)) {
            System.out.printf("   [SocialGraph] %s already follows %s — skipping%n",
                    follower.getName(), followee.getName());
            return;
        }

        // Prevent self-follow
        if (followerId.equals(followeeId)) {
            System.out.println("   [SocialGraph] Cannot follow yourself");
            return;
        }

        // Create the follow edge
        Follow follow = new Follow(followerId, followeeId);
        followRepository.save(follow);

        // Update denormalized counters
        follower.setFollowingCount(followRepository.countFollowing(followerId));
        followee.setFollowerCount(followRepository.countFollowers(followeeId));

        System.out.printf("   [SocialGraph] %s -> follows -> %s (followers: %d, following: %d)%n",
                follower.getName(), followee.getName(),
                followee.getFollowerCount(), follower.getFollowingCount());
    }

    /**
     * User A unfollows User B.
     * Removes the directed edge and updates counters.
     */
    public void unfollow(String followerId, String followeeId) {
        User follower = userRepository.findById(followerId)
                .orElseThrow(() -> new UserNotFoundException(followerId));
        User followee = userRepository.findById(followeeId)
                .orElseThrow(() -> new UserNotFoundException(followeeId));

        if (!followRepository.exists(followerId, followeeId)) {
            System.out.printf("   [SocialGraph] %s does not follow %s — skipping%n",
                    follower.getName(), followee.getName());
            return;
        }

        followRepository.delete(followerId, followeeId);

        // Update denormalized counters
        follower.setFollowingCount(followRepository.countFollowing(followerId));
        followee.setFollowerCount(followRepository.countFollowers(followeeId));

        System.out.printf("   [SocialGraph] %s unfollowed %s%n",
                follower.getName(), followee.getName());
    }

    /**
     * Get all follower IDs for a user.
     * Used by FanoutService to push posts to followers.
     */
    public List<String> getFollowers(String userId) {
        return followRepository.findFollowerIds(userId);
    }

    /**
     * Get all following IDs for a user.
     * Used by FeedService to pull celebrity posts at read time.
     */
    public List<String> getFollowing(String userId) {
        return followRepository.findFollowingIds(userId);
    }

    /**
     * Check if user A follows user B.
     */
    public boolean isFollowing(String followerId, String followeeId) {
        return followRepository.exists(followerId, followeeId);
    }

    /**
     * Check if a user is a celebrity (followerCount > 10,000).
     */
    public boolean isCelebrity(String userId) {
        return userRepository.findById(userId)
                .map(User::isCelebrity)
                .orElse(false);
    }

    public long getFollowCount() {
        return followRepository.count();
    }
}
