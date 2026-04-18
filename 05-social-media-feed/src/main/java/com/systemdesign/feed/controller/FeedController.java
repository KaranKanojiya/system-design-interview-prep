package com.systemdesign.feed.controller;

import com.systemdesign.feed.model.FeedItem;
import com.systemdesign.feed.model.TrendingTopic;
import com.systemdesign.feed.model.Tweet;
import com.systemdesign.feed.service.FeedService;
import com.systemdesign.feed.service.FollowService;
import com.systemdesign.feed.service.TweetService;
import com.systemdesign.feed.trending.TrendingService;

import java.util.Collections;
import java.util.List;

/**
 * Simulated REST controller -- delegates to service layer.
 */
public class FeedController {

    private final TweetService tweetService;
    private final FeedService feedService;
    private final FollowService followService;
    private final TrendingService trendingService;

    public FeedController(TweetService tweetService, FeedService feedService,
                          FollowService followService, TrendingService trendingService) {
        this.tweetService = tweetService;
        this.feedService = feedService;
        this.followService = followService;
        this.trendingService = trendingService;
    }

    public Tweet handlePostTweet(String userId, String content) {
        System.out.println("  [POST /api/tweets]");
        return tweetService.postTweet(userId, content, Collections.emptyList());
    }

    public Tweet handlePostTweet(String userId, String content, List<String> mediaUrls) {
        System.out.println("  [POST /api/tweets]");
        return tweetService.postTweet(userId, content, mediaUrls);
    }

    public List<FeedItem> handleGetFeed(String userId, int limit) {
        System.out.println("  [GET /api/feed?userId=" + userId + "&limit=" + limit + "]");
        List<FeedItem> feed = feedService.generateFeed(userId, limit);
        printFeed(feed);
        return feed;
    }

    public List<FeedItem> handleGetUserTimeline(String userId, int limit) {
        System.out.println("  [GET /api/users/" + userId + "/tweets?limit=" + limit + "]");
        List<FeedItem> timeline = feedService.getUserTimeline(userId, limit);
        printFeed(timeline);
        return timeline;
    }

    public void handleFollow(String followerId, String followeeId) {
        System.out.println("  [POST /api/users/" + followeeId + "/follow]");
        followService.follow(followerId, followeeId);
    }

    public void handleUnfollow(String followerId, String followeeId) {
        System.out.println("  [DELETE /api/users/" + followeeId + "/follow]");
        followService.unfollow(followerId, followeeId);
    }

    public void handleLike(String tweetId, String userId) {
        System.out.println("  [POST /api/tweets/" + tweetId + "/like]");
        tweetService.likeTweet(tweetId, userId);
    }

    public void handleRetweet(String tweetId, String userId) {
        System.out.println("  [POST /api/tweets/" + tweetId + "/retweet]");
        tweetService.retweetTweet(tweetId, userId);
    }

    public List<TrendingTopic> handleGetTrending(int limit) {
        System.out.println("  [GET /api/trending?limit=" + limit + "]");
        trendingService.printTrending(limit);
        return trendingService.getTopTrending(limit);
    }

    public void handleDeleteTweet(String tweetId) {
        System.out.println("  [DELETE /api/tweets/" + tweetId + "]");
        tweetService.deleteTweet(tweetId);
    }

    private void printFeed(List<FeedItem> feed) {
        if (feed.isEmpty()) {
            System.out.println("    (empty feed)");
            return;
        }
        for (int i = 0; i < feed.size(); i++) {
            System.out.println("    " + (i + 1) + ". " + feed.get(i));
        }
    }

    // Expose services for demo access
    public TweetService getTweetService() { return tweetService; }
    public FeedService getFeedService() { return feedService; }
    public FollowService getFollowService() { return followService; }
    public TrendingService getTrendingService() { return trendingService; }
}
