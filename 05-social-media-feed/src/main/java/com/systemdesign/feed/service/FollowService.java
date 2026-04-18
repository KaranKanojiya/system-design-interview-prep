package com.systemdesign.feed.service;

import com.systemdesign.feed.exception.UserNotFoundException;
import com.systemdesign.feed.model.Follow;
import com.systemdesign.feed.model.User;
import com.systemdesign.feed.repository.FollowRepository;
import com.systemdesign.feed.repository.UserRepository;

import java.util.List;

public class FollowService {

    private final FollowRepository followRepo;
    private final UserRepository userRepo;

    public FollowService(FollowRepository followRepo, UserRepository userRepo) {
        this.followRepo = followRepo;
        this.userRepo = userRepo;
    }

    /**
     * User followerId follows user followeeId.
     */
    public void follow(String followerId, String followeeId) {
        User follower = userRepo.findById(followerId)
                .orElseThrow(() -> new UserNotFoundException(followerId));
        User followee = userRepo.findById(followeeId)
                .orElseThrow(() -> new UserNotFoundException(followeeId));

        if (followRepo.isFollowing(followerId, followeeId)) {
            System.out.println("  [FOLLOW] @" + follower.getUsername()
                    + " already follows @" + followee.getUsername());
            return;
        }

        followRepo.save(new Follow(followerId, followeeId));
        followee.incrementFollowers();
        follower.incrementFollowing();

        System.out.println("  [FOLLOW] @" + follower.getUsername()
                + " followed @" + followee.getUsername()
                + " (now " + String.format("%,d", followee.getFollowerCount()) + " followers)");
    }

    /**
     * User followerId unfollows user followeeId.
     */
    public void unfollow(String followerId, String followeeId) {
        User follower = userRepo.findById(followerId)
                .orElseThrow(() -> new UserNotFoundException(followerId));
        User followee = userRepo.findById(followeeId)
                .orElseThrow(() -> new UserNotFoundException(followeeId));

        if (!followRepo.isFollowing(followerId, followeeId)) {
            System.out.println("  [UNFOLLOW] @" + follower.getUsername()
                    + " does not follow @" + followee.getUsername());
            return;
        }

        followRepo.delete(followerId, followeeId);
        followee.decrementFollowers();
        follower.decrementFollowing();

        System.out.println("  [UNFOLLOW] @" + follower.getUsername()
                + " unfollowed @" + followee.getUsername()
                + " (now " + String.format("%,d", followee.getFollowerCount()) + " followers)");
    }

    public List<String> getFollowers(String userId) {
        return followRepo.getFollowerIds(userId);
    }

    public List<String> getFollowing(String userId) {
        return followRepo.getFolloweeIds(userId);
    }
}
