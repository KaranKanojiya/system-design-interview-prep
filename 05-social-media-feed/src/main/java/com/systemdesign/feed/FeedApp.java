package com.systemdesign.feed;

import com.systemdesign.feed.config.AppConfig;
import com.systemdesign.feed.controller.FeedController;
import com.systemdesign.feed.model.FeedItem;
import com.systemdesign.feed.model.Tweet;
import com.systemdesign.feed.model.User;
import com.systemdesign.feed.model.UserType;

import java.util.List;

/**
 * Social Media Feed System -- System Design Demo
 *
 * Demonstrates:
 * 1. The Celebrity Problem and why naive fan-out-on-write fails at scale
 * 2. Hybrid Fan-out (write for normal, read for celebrity) -- what Twitter/X uses
 * 3. Feed generation by merging pre-computed + pulled tweets
 * 4. Engagement-based ranking with time decay
 * 5. Trending topics via hashtag counting
 * 6. Edge cases: deletion, unfollow, celebrity threshold crossing
 */
public class FeedApp {

    public static void main(String[] args) throws InterruptedException {
        printBanner();

        // ─── Setup ───────────────────────────────────────────────
        System.out.println("\n=== SETUP: Initializing system, seeding users and follows ===\n");
        AppConfig config = new AppConfig();
        FeedController controller = config.getController();

        // Print user roster
        System.out.println("\n--- User Roster ---");
        List<User> allUsers = config.getUserRepo().findAll();
        allUsers.stream()
                .sorted((a, b) -> Integer.compare(b.getFollowerCount(), a.getFollowerCount()))
                .forEach(u -> System.out.println("  " + u));
        System.out.println("  Celebrity threshold: " + String.format("%,d", User.CELEBRITY_THRESHOLD) + " followers\n");

        // Small sleep between demos to create visible time differences for ranking
        Thread.sleep(50);

        // ═══════════════════════════════════════════════════════════
        // DEMO 1: Normal User Posts -- Fan-out on Write (Push)
        // ═══════════════════════════════════════════════════════════
        printSection("DEMO 1: NORMAL USER POSTS -- Fan-out on Write (Push Model)");
        System.out.println("  Bob (200 followers) posts a tweet.");
        System.out.println("  Since Bob is NORMAL, the system PUSHES the tweet to all followers' caches.\n");

        Tweet bobTweet1 = controller.handlePostTweet("bob", "Just had amazing coffee! #morning #coffee");

        System.out.println("\n  Alice checks her feed (she follows Bob):");
        controller.handleGetFeed("alice", 10);

        System.out.println("\n  Carol checks her feed (she also follows Bob):");
        controller.handleGetFeed("carol", 10);

        System.out.println("\n  KEY INSIGHT: Bob's tweet was pre-computed into Alice's and Carol's caches");
        System.out.println("  at WRITE time. When they read their feed, no extra work is needed.\n");

        Thread.sleep(50);

        // ═══════════════════════════════════════════════════════════
        // DEMO 2: Celebrity Posts -- Fan-out on Read (Pull Model)
        //         THE CELEBRITY PROBLEM
        // ═══════════════════════════════════════════════════════════
        printSection("DEMO 2: CELEBRITY POSTS -- Fan-out on Read (The Celebrity Problem)");
        System.out.println("  Elon Musk (50,000,000 followers) posts a tweet.");
        System.out.println("  If we used fan-out-on-write, we'd need to update 50 MILLION timeline caches!");
        System.out.println("  Instead, the hybrid system uses fan-out-on-READ for celebrities.\n");

        Tweet elonTweet1 = controller.handlePostTweet("elon", "Going to Mars next year! #SpaceX #Mars");

        System.out.println("\n  >>> 50,000,000 timeline writes AVOIDED by using fan-out-on-read <<<");
        System.out.println("\n  Alice checks her feed -- Elon's tweet is PULLED at read time:");
        List<FeedItem> aliceFeed1 = controller.handleGetFeed("alice", 10);

        System.out.println("\n  KEY INSIGHT: Elon's tweet was NOT pushed to any timeline cache.");
        System.out.println("  When Alice opens her feed, the system PULLS Elon's latest tweets on the fly.");
        System.out.println("  This avoids 50M writes at the cost of a small read-time query.\n");

        Thread.sleep(50);

        // ═══════════════════════════════════════════════════════════
        // DEMO 3: Hybrid Feed Generation -- Show the Merge
        // ═══════════════════════════════════════════════════════════
        printSection("DEMO 3: HYBRID FEED -- Merging Pushed + Pulled Tweets");
        System.out.println("  Alice follows: bob (normal), carol (normal), elon (celebrity), taylor (celebrity)");
        System.out.println("  Normal users' tweets -> PUSHED to Alice's cache at write time");
        System.out.println("  Celebrity users' tweets -> PULLED from their timelines at read time\n");

        // Carol and Taylor also post
        Tweet carolTweet1 = controller.handlePostTweet("carol", "Loving the new album! #music #vibes");
        System.out.println();
        Thread.sleep(50);
        Tweet taylorTweet1 = controller.handlePostTweet("taylor", "New album dropping Friday! #Swifties #music");

        System.out.println("\n  Now Alice opens her feed -- the system MERGES everything:");
        System.out.println("  - Pre-computed (pushed): Bob's tweet, Carol's tweet");
        System.out.println("  - Pulled at read-time: Elon's tweet, Taylor's tweet\n");

        List<FeedItem> aliceFeed2 = controller.handleGetFeed("alice", 10);

        System.out.println("\n  NOTICE the [FANOUT_WRITE] vs [FANOUT_READ] source labels above.");
        System.out.println("  This is the hybrid fan-out in action!\n");

        Thread.sleep(50);

        // ═══════════════════════════════════════════════════════════
        // DEMO 4: Engagement Ranking
        // ═══════════════════════════════════════════════════════════
        printSection("DEMO 4: ENGAGEMENT RANKING -- Likes and Retweets Change Order");
        System.out.println("  Adding engagement to some tweets to show how ranking changes.\n");

        // Elon's tweet gets massive engagement
        controller.handleLike(elonTweet1.getTweetId(), "alice");
        controller.handleLike(elonTweet1.getTweetId(), "bob");
        controller.handleLike(elonTweet1.getTweetId(), "carol");
        controller.handleRetweet(elonTweet1.getTweetId(), "alice");
        controller.handleRetweet(elonTweet1.getTweetId(), "bob");

        // Bob's tweet gets some engagement
        controller.handleLike(bobTweet1.getTweetId(), "alice");
        controller.handleRetweet(bobTweet1.getTweetId(), "carol");

        System.out.println("\n  Engagement scores:");
        System.out.println("    Elon's tweet: " + elonTweet1.getLikeCount() + " likes, "
                + elonTweet1.getRetweetCount() + " retweets -> engagement = "
                + String.format("%.1f", elonTweet1.getEngagementScore()));
        System.out.println("    Bob's tweet: " + bobTweet1.getLikeCount() + " likes, "
                + bobTweet1.getRetweetCount() + " retweets -> engagement = "
                + String.format("%.1f", bobTweet1.getEngagementScore()));
        System.out.println("    Carol's tweet: " + carolTweet1.getLikeCount() + " likes -> engagement = "
                + String.format("%.1f", carolTweet1.getEngagementScore()));

        System.out.println("\n  Alice's re-ranked feed (engagement-based with time decay):");
        controller.handleGetFeed("alice", 10);

        System.out.println("\n  KEY INSIGHT: Elon's tweet (high engagement) ranks higher despite being older.");
        System.out.println("  The formula: score = freshnessFactor * (likes*1.0 + retweets*2.0 + replies*1.5 + 1)\n");

        // ═══════════════════════════════════════════════════════════
        // DEMO 5: Trending Topics
        // ═══════════════════════════════════════════════════════════
        printSection("DEMO 5: TRENDING TOPICS");
        System.out.println("  Hashtags seen so far across all tweets:\n");

        // Post another tweet with overlapping hashtags to boost counts
        controller.handlePostTweet("bob", "SpaceX launch was incredible! #SpaceX #rockets");
        System.out.println();

        controller.handleGetTrending(10);
        System.out.println();

        // ═══════════════════════════════════════════════════════════
        // DEMO 6: Edge Cases
        // ═══════════════════════════════════════════════════════════
        printSection("DEMO 6: EDGE CASES");

        // 6a: Tweet Deletion
        System.out.println("  --- 6a: Tweet Deletion ---");
        System.out.println("  Bob deletes his coffee tweet. It should be filtered from feeds.\n");
        controller.handleDeleteTweet(bobTweet1.getTweetId());
        System.out.println("\n  Alice's feed after deletion (Bob's coffee tweet is gone):");
        controller.handleGetFeed("alice", 10);

        // 6b: Unfollow
        System.out.println("\n  --- 6b: Unfollow ---");
        System.out.println("  Alice unfollows Bob.\n");
        controller.handleUnfollow("alice", "bob");
        System.out.println("\n  Alice's feed after unfollowing Bob (Bob's remaining tweets may still");
        System.out.println("  appear from cache until cache eviction, but no new tweets will be pushed):");
        controller.handleGetFeed("alice", 10);

        // 6c: Celebrity Threshold Crossing
        System.out.println("\n  --- 6c: Celebrity Threshold Crossing ---");
        System.out.println("  Creating user 'rising_star' with 9,999 followers (just below threshold).\n");

        User risingstar = new User("risingstar", "rising_star", "Rising Star",
                "Almost famous", 9_999, 0, UserType.NORMAL);
        config.getUserRepo().save(risingstar);
        System.out.println("  " + risingstar);

        System.out.println("\n  A new follower follows rising_star, pushing them to 10,000...");
        config.getFollowService().follow("alice", "risingstar");

        System.out.println("  " + config.getUserRepo().findById("risingstar").orElseThrow());
        System.out.println("\n  rising_star is now a CELEBRITY! Future tweets use fan-out-on-read.\n");

        // rising_star posts a tweet -- should use read strategy
        System.out.println("  rising_star posts a tweet as a newly-minted celebrity:");
        controller.handlePostTweet("risingstar", "I made it! Thanks for 10K followers! #milestone");

        // ═══════════════════════════════════════════════════════════
        // Summary
        // ═══════════════════════════════════════════════════════════
        printDesignSummary();
    }

    private static void printBanner() {
        System.out.println("================================================================");
        System.out.println("   SOCIAL MEDIA FEED SYSTEM -- System Design Interview Demo");
        System.out.println("   Twitter/X-like Feed with Hybrid Fan-out Architecture");
        System.out.println("================================================================");
    }

    private static void printSection(String title) {
        System.out.println("================================================================");
        System.out.println("  " + title);
        System.out.println("================================================================\n");
    }

    private static void printDesignSummary() {
        System.out.println("\n================================================================");
        System.out.println("  DESIGN SUMMARY");
        System.out.println("================================================================\n");
        System.out.println("  THE CELEBRITY PROBLEM:");
        System.out.println("  When a celebrity with millions of followers posts, writing to");
        System.out.println("  every follower's timeline is prohibitively expensive.");
        System.out.println("  e.g., Elon posts -> 50M writes? That takes minutes and huge I/O.\n");

        System.out.println("  THE SOLUTION -- HYBRID FAN-OUT:");
        System.out.println("  +-------------------+------------------+--------------------+");
        System.out.println("  |                   | Fan-out on WRITE | Fan-out on READ    |");
        System.out.println("  +-------------------+------------------+--------------------+");
        System.out.println("  | When tweet posted | Push to all      | Do nothing         |");
        System.out.println("  |                   | followers' caches|                    |");
        System.out.println("  +-------------------+------------------+--------------------+");
        System.out.println("  | When feed read    | Already cached,  | Pull from poster's |");
        System.out.println("  |                   | instant read     | timeline + merge   |");
        System.out.println("  +-------------------+------------------+--------------------+");
        System.out.println("  | Best for          | Normal users     | Celebrities        |");
        System.out.println("  |                   | (few followers)  | (millions of       |");
        System.out.println("  |                   |                  | followers)          |");
        System.out.println("  +-------------------+------------------+--------------------+\n");

        System.out.println("  HYBRID = Write for Normal + Read for Celebrity");
        System.out.println("  At read time, MERGE pre-computed cache with pulled celebrity tweets.\n");

        System.out.println("  ARCHITECTURE LAYERS:");
        System.out.println("    1. Model:      User, Tweet, FeedItem, Follow, TrendingTopic");
        System.out.println("    2. Repository:  In-memory stores (ConcurrentHashMap-based)");
        System.out.println("    3. Fan-out:     HybridFanoutStrategy (write + read strategies)");
        System.out.println("    4. Ranking:     Engagement-based with time decay");
        System.out.println("    5. Trending:    Hashtag counting with top-K retrieval");
        System.out.println("    6. Service:     TweetService, FeedService, FollowService");
        System.out.println("    7. Controller:  Simulated REST endpoints\n");

        System.out.println("  KEY NUMBERS (at Twitter/X scale):");
        System.out.println("    - 500M tweets/day");
        System.out.println("    - Average user: ~300 followers -> fan-out-on-write is fine");
        System.out.println("    - Top celebrity: ~150M followers -> fan-out-on-read is essential");
        System.out.println("    - Celebrity threshold: typically 10K-100K followers\n");

        System.out.println("================================================================");
        System.out.println("  END OF DEMO");
        System.out.println("================================================================");
    }
}
