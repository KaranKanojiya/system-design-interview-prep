package com.systemdesign.feed.trending;

import com.systemdesign.feed.model.TrendingTopic;
import com.systemdesign.feed.model.Tweet;
import com.systemdesign.feed.repository.TrendingRepository;

import java.util.List;

/**
 * Manages trending topic tracking.
 */
public class TrendingService {

    private final TrendingRepository trendingRepo;

    public TrendingService(TrendingRepository trendingRepo) {
        this.trendingRepo = trendingRepo;
    }

    /**
     * Records hashtags from a tweet into the trending counters.
     */
    public void recordTweet(Tweet tweet) {
        List<String> hashtags = HashtagExtractor.extract(tweet.getContent());
        for (String hashtag : hashtags) {
            trendingRepo.incrementHashtag(hashtag);
        }
    }

    /**
     * Returns the top trending hashtags.
     */
    public List<TrendingTopic> getTopTrending(int limit) {
        return trendingRepo.getTopTrending(limit);
    }

    /**
     * Prints a formatted list of trending topics.
     */
    public void printTrending(int limit) {
        List<TrendingTopic> topics = getTopTrending(limit);
        System.out.println("  Trending Topics:");
        if (topics.isEmpty()) {
            System.out.println("    (no trending topics yet)");
            return;
        }
        for (int i = 0; i < topics.size(); i++) {
            System.out.println("    " + (i + 1) + ". " + topics.get(i));
        }
    }
}
