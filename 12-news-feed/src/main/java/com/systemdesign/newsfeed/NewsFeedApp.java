package com.systemdesign.newsfeed;

import com.systemdesign.newsfeed.config.AppConfig;
import com.systemdesign.newsfeed.controller.FeedController;
import com.systemdesign.newsfeed.model.ContentType;
import com.systemdesign.newsfeed.model.FeedCursor;
import com.systemdesign.newsfeed.model.FeedItem;
import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.service.FeedService;
import com.systemdesign.newsfeed.service.PostService;
import com.systemdesign.newsfeed.service.RankingService;
import com.systemdesign.newsfeed.strategy.fanout.HybridFanoutStrategy;
import com.systemdesign.newsfeed.strategy.ranking.AlgorithmicRankingStrategy;
import com.systemdesign.newsfeed.strategy.ranking.ChronologicalRankingStrategy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * News Feed System — System Design Interview Demo
 *
 * Demonstrates the core design decisions behind Facebook/LinkedIn/Instagram feeds:
 *
 * 1. HYBRID FAN-OUT: Normal users -> push (fan-out on write), Celebrities -> pull (fan-out on read)
 * 2. RANKING: Algorithmic (affinity * recency * engagement * contentType) vs Chronological
 * 3. CURSOR PAGINATION: Stable infinite scroll without duplicates
 * 4. TIMELINE CACHE: Pre-computed feeds with capped eviction
 *
 * Architecture:
 *   PostService  -> FanoutService -> HybridFanoutStrategy -> TimelineCache
 *   FeedService  -> TimelineService + CelebrityPull + RankingService -> FeedItems
 *   EngagementService -> Post counters + Affinity tracking
 *
 * Patterns used:
 *   - Strategy (fan-out + ranking)
 *   - Composite (HybridFanoutStrategy wraps write + read strategies)
 *   - Builder (Post)
 *   - Facade (FeedService orchestrates the entire read path)
 *   - Repository (data access abstraction)
 *   - Factory (AppConfig wires everything)
 *   - Cursor-based pagination (FeedCursor)
 */
public class NewsFeedApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("   NEWS FEED SYSTEM — System Design Interview Demo");
        System.out.println("   (Facebook / LinkedIn / Instagram style)");
        System.out.println(SEPARATOR);
        System.out.println();

        // --- Bootstrap: AppConfig creates ALL objects (Composition Root) ---
        AppConfig config = new AppConfig();
        FeedController controller = config.getFeedController();

        // Run all demos
        demo1_SocialGraph(controller);
        demo2_PostPublishingWithFanout(controller, config);
        demo3_CelebrityProblem(controller, config);
        demo4_HybridFanoutStats(config);
        demo5_ChronologicalVsAlgorithmic(controller, config);
        demo6_EngagementScoring(controller, config);
        demo7_InfiniteScrollPagination(controller, config);
        demo8_ContentTypeRanking(controller, config);
        demo9_RealTimeFeedUpdate(controller, config);
        demo10_FeedGenerationPerformance(controller, config);

        // Final summary
        printDesignSummary();
    }

    // ============================================================
    // DEMO 1: Social Graph
    // ============================================================
    private static void demo1_SocialGraph(FeedController controller) {
        printSection("DEMO 1: Social Graph — Follow/Unfollow");
        System.out.println("Building the social graph: who follows whom?");
        System.out.println("This determines whose posts appear in your feed.");
        System.out.println();

        // Normal users follow each other
        controller.handleFollow("alice", "bob");
        controller.handleFollow("alice", "charlie");
        controller.handleFollow("bob", "alice");
        controller.handleFollow("bob", "charlie");
        controller.handleFollow("charlie", "alice");
        controller.handleFollow("diana", "alice");
        controller.handleFollow("diana", "bob");
        controller.handleFollow("eve", "alice");

        // Everyone follows celebrities
        controller.handleFollow("alice", "elon");
        controller.handleFollow("alice", "taylor");
        controller.handleFollow("bob", "elon");
        controller.handleFollow("bob", "cristiano");
        controller.handleFollow("charlie", "taylor");
        controller.handleFollow("diana", "elon");
        controller.handleFollow("diana", "taylor");
        controller.handleFollow("diana", "cristiano");
        controller.handleFollow("eve", "elon");
        controller.handleFollow("eve", "taylor");
        controller.handleFollow("eve", "cristiano");

        System.out.println();
        System.out.println("Social graph built. Alice follows: bob, charlie, elon, taylor");
        System.out.println("Bob follows: alice, charlie, elon, cristiano");
        System.out.println();
    }

    // ============================================================
    // DEMO 2: Post Publishing with Fan-out
    // ============================================================
    private static void demo2_PostPublishingWithFanout(FeedController controller, AppConfig config) {
        printSection("DEMO 2: Post Publishing — Fan-out on Write (Normal Users)");
        System.out.println("When a NORMAL user posts, the post is PUSHED to all followers' timelines.");
        System.out.println("This is fan-out on write: O(followers) write cost, O(1) read cost.");
        System.out.println();

        // Alice posts (normal user -> fan-out on write -> pushed to bob, charlie, diana, eve)
        Post alicePost = controller.handleCreatePost("alice", "Just had an amazing coffee at the new cafe downtown!", ContentType.TEXT, null);
        System.out.println();

        // Bob posts (normal user -> fan-out on write -> pushed to alice, charlie)
        // Note: only alice, charlie, diana follow Bob — NOT everyone
        Post bobPost = controller.handleCreatePost("bob", "Check out this sunset photo!", ContentType.IMAGE, "https://photos.example.com/sunset.jpg");
        System.out.println();

        // Charlie posts
        Post charliePost = controller.handleCreatePost("charlie", "New blog post about distributed systems", ContentType.LINK, "https://blog.example.com/distributed-systems");
        System.out.println();

        // Verify: Alice's timeline should have bob's and charlie's posts (pushed via fan-out)
        System.out.println("Checking Alice's timeline cache (should have Bob's and Charlie's posts):");
        int aliceTimelineSize = config.getTimelineService().getTimelineSize("alice");
        System.out.printf("   Alice's timeline size: %d entries%n", aliceTimelineSize);
        System.out.println();
    }

    // ============================================================
    // DEMO 3: Celebrity Problem
    // ============================================================
    private static void demo3_CelebrityProblem(FeedController controller, AppConfig config) {
        printSection("DEMO 3: The Celebrity Problem — Fan-out on Read");
        System.out.println("When a CELEBRITY posts, we do NOT push to followers' timelines.");
        System.out.println("Why? Because pushing to 50,000+ timelines per post is too expensive.");
        System.out.println("Instead, celebrity posts are PULLED at read time when a follower opens their feed.");
        System.out.println();

        // Elon posts (celebrity -> fan-out on read -> NO push, pulled at read time)
        Post elonPost = controller.handleCreatePost("elon", "Just launched another rocket! #SpaceX", ContentType.TEXT, null);
        System.out.println();

        // Taylor posts (celebrity -> fan-out on read -> NO push)
        Post taylorPost = controller.handleCreatePost("taylor", "New album dropping next Friday!", ContentType.VIDEO, "https://video.example.com/album-teaser.mp4");
        System.out.println();

        // Cristiano posts (celebrity -> fan-out on read -> NO push)
        Post cristianoPost = controller.handleCreatePost("cristiano", "Hat-trick today! What a game!", ContentType.IMAGE, "https://photos.example.com/hattrick.jpg");
        System.out.println();

        // Verify: Alice's timeline should NOT have celebrity posts (they weren't pushed)
        System.out.println("Checking Alice's timeline cache after celebrity posts:");
        int aliceTimelineSize = config.getTimelineService().getTimelineSize("alice");
        System.out.printf("   Alice's timeline size: %d entries (no celebrity posts pushed!)%n", aliceTimelineSize);
        System.out.println();

        // But when Alice reads her feed, celebrity posts ARE included (pulled on read)
        System.out.println("Alice opens her feed -> celebrity posts are PULLED at read time:");
        List<FeedItem> aliceFeed = controller.handleGetFeed("alice", FeedCursor.firstPage(10));
        System.out.printf("   Alice's feed has %d items (includes pulled celebrity posts)%n", aliceFeed.size());
        for (FeedItem item : aliceFeed) {
            System.out.printf("   [%s] %s: '%.40s' (score=%.4f)%n",
                    item.getSource(), item.getPost().getAuthorName(),
                    item.getPost().getContent(), item.getScore());
        }
        System.out.println();
    }

    // ============================================================
    // DEMO 4: Hybrid Fan-out Stats
    // ============================================================
    private static void demo4_HybridFanoutStats(AppConfig config) {
        printSection("DEMO 4: Hybrid Fan-out Stats");
        System.out.println("The HybridFanoutStrategy routes posts based on author's celebrity status:");
        System.out.println("  Normal users  -> Fan-out on WRITE (push to all followers)");
        System.out.println("  Celebrities   -> Fan-out on READ  (no-op, pulled later)");
        System.out.println();

        HybridFanoutStrategy hybrid = config.getHybridFanoutStrategy();
        System.out.println("Stats so far:");
        System.out.printf("   Write path (normal users): %d posts pushed to followers%n", hybrid.getWritePathCount());
        System.out.printf("   Read path (celebrities):   %d posts skipped (pulled on read)%n", hybrid.getReadPathCount());
        System.out.printf("   Total fan-outs:            %d%n", hybrid.getWritePathCount() + hybrid.getReadPathCount());
        System.out.println();
        System.out.println("Key insight: if 90%% of posts are from normal users, 90%% of fan-outs");
        System.out.println("use the write path (fast reads). Only 10%% use the read path.");
        System.out.println();
    }

    // ============================================================
    // DEMO 5: Chronological vs Algorithmic Feed
    // ============================================================
    private static void demo5_ChronologicalVsAlgorithmic(FeedController controller, AppConfig config) {
        printSection("DEMO 5: Chronological vs Algorithmic Ranking");
        System.out.println("Same posts, different ordering!");
        System.out.println();

        RankingService rankingService = config.getRankingService();
        AlgorithmicRankingStrategy algorithmic = config.getAlgorithmicRankingStrategy();
        ChronologicalRankingStrategy chronological = config.getChronologicalRankingStrategy();

        // Show chronological feed
        System.out.println("--- CHRONOLOGICAL FEED (newest first, like Twitter classic) ---");
        rankingService.setStrategy(chronological);
        List<FeedItem> chronoFeed = controller.handleGetFeed("alice", FeedCursor.firstPage(10));
        for (FeedItem item : chronoFeed) {
            System.out.printf("   #%d [score=%.2f] %s (%s): '%.40s'%n",
                    item.getPosition(), item.getScore(),
                    item.getPost().getAuthorName(), item.getPost().getContentType(),
                    item.getPost().getContent());
        }
        System.out.println();

        // Show algorithmic feed
        System.out.println("--- ALGORITHMIC FEED (relevance-based, like Facebook/Instagram) ---");
        rankingService.setStrategy(algorithmic);
        List<FeedItem> algoFeed = controller.handleGetFeed("alice", FeedCursor.firstPage(10));
        for (FeedItem item : algoFeed) {
            System.out.printf("   #%d [score=%.4f] %s (%s): '%.40s'%n",
                    item.getPosition(), item.getScore(),
                    item.getPost().getAuthorName(), item.getPost().getContentType(),
                    item.getPost().getContent());
        }
        System.out.println();
        System.out.println("Notice: algorithmic ranking considers content type weight,");
        System.out.println("engagement score, recency decay, and affinity (interaction history).");
        System.out.println();
    }

    // ============================================================
    // DEMO 6: Engagement Scoring
    // ============================================================
    private static void demo6_EngagementScoring(FeedController controller, AppConfig config) {
        printSection("DEMO 6: Engagement Scoring — Likes/Comments/Shares Change Rankings");
        System.out.println("When users engage with posts, the ranking scores change.");
        System.out.println("Engagement formula: likes*1 + comments*2 + shares*3");
        System.out.println();

        // Get current feed to find post IDs
        RankingService rankingService = config.getRankingService();
        rankingService.setStrategy(config.getAlgorithmicRankingStrategy());

        List<FeedItem> feedBefore = controller.handleGetFeed("alice", FeedCursor.firstPage(10));
        if (feedBefore.size() < 2) {
            System.out.println("   Not enough posts for engagement demo.");
            return;
        }

        // Pick the LOWEST ranked post and boost it with engagement
        FeedItem lowestItem = feedBefore.get(feedBefore.size() - 1);
        String boostPostId = lowestItem.getPost().getPostId();
        System.out.printf("Lowest ranked post: '%s' by %s (score=%.4f)%n",
                lowestItem.getPost().getContent().substring(0, Math.min(40, lowestItem.getPost().getContent().length())),
                lowestItem.getPost().getAuthorName(), lowestItem.getScore());
        System.out.println();

        // Boost it with lots of engagement
        System.out.println("Adding engagement to boost this post:");
        controller.handleLike("alice", boostPostId);
        controller.handleLike("bob", boostPostId);
        controller.handleLike("charlie", boostPostId);
        controller.handleLike("diana", boostPostId);
        controller.handleLike("eve", boostPostId);
        controller.handleComment("bob", boostPostId, "This is amazing!");
        controller.handleComment("charlie", boostPostId, "Totally agree!");
        controller.handleComment("diana", boostPostId, "Love this!");
        controller.handleShare("eve", boostPostId);
        controller.handleShare("bob", boostPostId);
        System.out.println();

        // Check new ranking
        System.out.println("After engagement, re-ranking the feed:");
        List<FeedItem> feedAfter = controller.handleGetFeed("alice", FeedCursor.firstPage(10));
        for (FeedItem item : feedAfter) {
            String marker = item.getPost().getPostId().equals(boostPostId) ? " <-- BOOSTED" : "";
            System.out.printf("   #%d [score=%.4f] %s: '%.40s' (likes=%d, comments=%d, shares=%d)%s%n",
                    item.getPosition(), item.getScore(),
                    item.getPost().getAuthorName(),
                    item.getPost().getContent(),
                    item.getPost().getLikeCount(), item.getPost().getCommentCount(),
                    item.getPost().getShareCount(), marker);
        }
        System.out.println();
        System.out.println("The previously lowest-ranked post should now rank HIGHER due to engagement boost.");
        System.out.println();
    }

    // ============================================================
    // DEMO 7: Infinite Scroll with Cursor Pagination
    // ============================================================
    private static void demo7_InfiniteScrollPagination(FeedController controller, AppConfig config) {
        printSection("DEMO 7: Infinite Scroll — Cursor-Based Pagination");
        System.out.println("Cursor pagination avoids the offset problem (duplicates when new posts arrive).");
        System.out.println("Each page uses the LAST SEEN item as the cursor anchor.");
        System.out.println();

        // Create more posts to have enough for pagination
        PostService postService = config.getPostService();
        System.out.println("Creating additional posts for pagination demo...");
        for (int i = 1; i <= 8; i++) {
            postService.createPost("bob", "Bob's post #" + i + " for pagination demo", ContentType.TEXT, null);
        }
        for (int i = 1; i <= 5; i++) {
            postService.createPost("charlie", "Charlie's update #" + i, ContentType.TEXT, null);
        }
        System.out.println();

        // Page 1
        int pageSize = 5;
        System.out.printf("--- PAGE 1 (first %d items) ---%n", pageSize);
        FeedCursor cursor1 = FeedCursor.firstPage(pageSize);
        List<FeedItem> page1 = controller.handleGetFeed("alice", cursor1);
        printPage(page1, 1);

        if (page1.isEmpty()) {
            System.out.println("   No items in page 1.");
            return;
        }

        // Page 2: cursor points to last item of page 1
        FeedItem lastOfPage1 = page1.get(page1.size() - 1);
        System.out.printf("--- PAGE 2 (after post '%s') ---%n", lastOfPage1.getPost().getPostId());
        FeedCursor cursor2 = FeedCursor.nextPage(
                lastOfPage1.getPost().getPostId(),
                lastOfPage1.getPost().getCreatedAt(),
                pageSize);
        List<FeedItem> page2 = controller.handleGetFeed("alice", cursor2);
        printPage(page2, 2);

        // Page 3
        if (!page2.isEmpty()) {
            FeedItem lastOfPage2 = page2.get(page2.size() - 1);
            System.out.printf("--- PAGE 3 (after post '%s') ---%n", lastOfPage2.getPost().getPostId());
            FeedCursor cursor3 = FeedCursor.nextPage(
                    lastOfPage2.getPost().getPostId(),
                    lastOfPage2.getPost().getCreatedAt(),
                    pageSize);
            List<FeedItem> page3 = controller.handleGetFeed("alice", cursor3);
            printPage(page3, 3);
        }

        System.out.println();
        System.out.println("Key advantage: if new posts arrive between page loads,");
        System.out.println("the cursor still works correctly — no duplicates, no gaps.");
        System.out.println();
    }

    // ============================================================
    // DEMO 8: Content Type Ranking
    // ============================================================
    private static void demo8_ContentTypeRanking(FeedController controller, AppConfig config) {
        printSection("DEMO 8: Content Type Ranking — Video > Image > Text");
        System.out.println("Different content types have different ranking weights:");
        System.out.println("  VIDEO=1.5, POLL=1.4, IMAGE=1.3, LINK=1.1, TEXT=1.0");
        System.out.println("This means videos rank ~50%% higher than text posts, all else equal.");
        System.out.println();

        // Ensure algorithmic ranking is active
        config.getRankingService().setStrategy(config.getAlgorithmicRankingStrategy());

        // Create posts of each type at the same time by the same author
        // This isolates the content type weight effect
        PostService postService = config.getPostService();
        LocalDateTime sameTime = LocalDateTime.now();
        Post textPost = postService.createPostWithTimestamp("bob", "Plain text status update", ContentType.TEXT, null, sameTime);
        Post linkPost = postService.createPostWithTimestamp("bob", "Check out this article", ContentType.LINK, "https://example.com", sameTime);
        Post imagePost = postService.createPostWithTimestamp("bob", "Beautiful photo from my trip", ContentType.IMAGE, "https://photos.example.com/trip.jpg", sameTime);
        Post pollPost = postService.createPostWithTimestamp("bob", "Which do you prefer? Java or Python?", ContentType.POLL, null, sameTime);
        Post videoPost = postService.createPostWithTimestamp("bob", "Watch my new tutorial!", ContentType.VIDEO, "https://video.example.com/tutorial.mp4", sameTime);
        System.out.println();

        // Show Alice's feed — these 5 posts should be ordered by content type weight
        System.out.println("Alice's feed showing content type ranking (same author, same time):");
        List<FeedItem> feed = controller.handleGetFeed("alice", FeedCursor.firstPage(20));

        // Filter to show only the posts we just created
        System.out.println("   Content type ranking (filtered to same-time posts):");
        for (FeedItem item : feed) {
            if (item.getPost().getCreatedAt().equals(sameTime)) {
                System.out.printf("   #%d [score=%.4f] %s (weight=%.1f): '%.40s'%n",
                        item.getPosition(), item.getScore(),
                        item.getPost().getContentType(),
                        item.getPost().getContentType().getWeight(),
                        item.getPost().getContent());
            }
        }
        System.out.println();
        System.out.println("Expected order: VIDEO > POLL > IMAGE > LINK > TEXT");
        System.out.println("(because all other factors — recency, engagement, affinity — are equal)");
        System.out.println();
    }

    // ============================================================
    // DEMO 9: Real-time Feed Update Simulation
    // ============================================================
    private static void demo9_RealTimeFeedUpdate(FeedController controller, AppConfig config) {
        printSection("DEMO 9: Real-time Feed Update — 'New Posts Available' Banner");
        System.out.println("Simulates what happens when new posts arrive while viewing the feed.");
        System.out.println("In production, a WebSocket/SSE connection notifies the client.");
        System.out.println();

        // Step 1: Alice loads her feed
        System.out.println("Step 1: Alice loads her feed...");
        List<FeedItem> feedBefore = controller.handleGetFeed("alice", FeedCursor.firstPage(5));
        System.out.printf("   Alice sees %d items in her feed%n", feedBefore.size());
        if (!feedBefore.isEmpty()) {
            System.out.printf("   Top item: '%s' by %s%n",
                    feedBefore.get(0).getPost().getContent().substring(0, Math.min(40, feedBefore.get(0).getPost().getContent().length())),
                    feedBefore.get(0).getPost().getAuthorName());
        }
        System.out.println();

        // Step 2: While Alice is reading, new posts arrive
        System.out.println("Step 2: While Alice is reading, new posts arrive...");
        Post newPost1 = controller.handleCreatePost("bob", "BREAKING: Just saw something incredible!", ContentType.TEXT, null);
        Post newPost2 = controller.handleCreatePost("charlie", "Live from the conference!", ContentType.VIDEO, "https://video.example.com/live.mp4");
        System.out.println();

        // Step 3: "New posts available" banner
        System.out.println("Step 3: Alice's client detects new posts...");
        System.out.println("   ┌─────────────────────────────────────────┐");
        System.out.println("   │  2 new posts available — tap to refresh │");
        System.out.println("   └─────────────────────────────────────────┘");
        System.out.println();

        // Step 4: Alice refreshes — new posts at top
        System.out.println("Step 4: Alice taps refresh — feed reloads with new posts at top:");
        List<FeedItem> feedAfter = controller.handleGetFeed("alice", FeedCursor.firstPage(5));
        for (FeedItem item : feedAfter) {
            String isNew = (newPost1 != null && item.getPost().getPostId().equals(newPost1.getPostId())) ||
                           (newPost2 != null && item.getPost().getPostId().equals(newPost2.getPostId()))
                           ? " ** NEW **" : "";
            System.out.printf("   #%d %s: '%.40s'%s%n",
                    item.getPosition(), item.getPost().getAuthorName(),
                    item.getPost().getContent(), isNew);
        }
        System.out.println();
    }

    // ============================================================
    // DEMO 10: Feed Generation Performance
    // ============================================================
    private static void demo10_FeedGenerationPerformance(FeedController controller, AppConfig config) {
        printSection("DEMO 10: Feed Generation Performance");
        System.out.println("Measuring feed generation time for different user profiles.");
        System.out.println();

        // Measure feed generation for Alice (follows celebrities + normal users)
        System.out.println("--- Alice's feed (follows celebrities + normal users) ---");
        long start = System.nanoTime();
        int runs = 100;
        for (int i = 0; i < runs; i++) {
            controller.handleGetFeed("alice", FeedCursor.firstPage(20));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("   %d feed generations: %dms total, %.2fms avg%n",
                runs, elapsedMs, (double) elapsedMs / runs);
        System.out.println();

        // Measure for Bob (follows fewer celebrities)
        System.out.println("--- Bob's feed (follows fewer celebrities) ---");
        start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            controller.handleGetFeed("bob", FeedCursor.firstPage(20));
        }
        elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("   %d feed generations: %dms total, %.2fms avg%n",
                runs, elapsedMs, (double) elapsedMs / runs);
        System.out.println();

        // Measure for Eve (follows many celebrities)
        System.out.println("--- Eve's feed (follows many celebrities) ---");
        start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            controller.handleGetFeed("eve", FeedCursor.firstPage(20));
        }
        elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("   %d feed generations: %dms total, %.2fms avg%n",
                runs, elapsedMs, (double) elapsedMs / runs);
        System.out.println();

        System.out.println("Key insight: users who follow more celebrities have SLOWER feed generation");
        System.out.println("because celebrity posts must be PULLED at read time (fan-out on read cost).");
        System.out.println("Users who mostly follow normal users have FASTER feeds (pre-computed cache).");
        System.out.println();

        // Show overall system stats
        config.getFeedStatsDisplay().displayStats();
        System.out.println();
    }

    // ============================================================
    // Design Summary
    // ============================================================
    private static void printDesignSummary() {
        printSection("DESIGN SUMMARY — Interview Talking Points");
        System.out.println();
        System.out.println("1. FAN-OUT STRATEGY (The Celebrity Problem):");
        System.out.println("   - Normal users: Fan-out on WRITE (push to all followers' timelines)");
        System.out.println("   - Celebrities:  Fan-out on READ (pull at read time, no push)");
        System.out.println("   - Hybrid:       Route based on follower count threshold (10K)");
        System.out.println("   - This is what Facebook/Instagram actually use.");
        System.out.println();
        System.out.println("2. RANKING ALGORITHM:");
        System.out.println("   Score = Affinity * RecencyDecay * EngagementBoost * ContentTypeWeight");
        System.out.println("   - Affinity:    How often viewer interacts with author [1.0 - 2.0]");
        System.out.println("   - Recency:     exp(-0.05 * hoursAge) — exponential decay");
        System.out.println("   - Engagement:  1 + log(1 + likes + comments*2 + shares*3) / 10");
        System.out.println("   - ContentType: VIDEO=1.5 > POLL=1.4 > IMAGE=1.3 > LINK=1.1 > TEXT=1.0");
        System.out.println();
        System.out.println("3. PAGINATION (Cursor vs Offset):");
        System.out.println("   - Offset-based breaks when new posts are inserted (duplicates).");
        System.out.println("   - Cursor-based uses lastPostId + lastTimestamp as anchor.");
        System.out.println("   - Stable infinite scroll — no duplicates, no gaps.");
        System.out.println();
        System.out.println("4. TIMELINE CACHE:");
        System.out.println("   - Per-user sorted set of postIds (Redis ZSET in production).");
        System.out.println("   - Capped at 1000 entries (evict oldest).");
        System.out.println("   - Only stores postIds, not full posts (hydrate on read).");
        System.out.println();
        System.out.println("5. FEED ASSEMBLY PIPELINE:");
        System.out.println("   Timeline Cache (pre-computed) + Celebrity Pull (on-demand)");
        System.out.println("   -> Merge -> Deduplicate -> Rank -> Paginate -> Return");
        System.out.println();
        System.out.println("6. SCALE CONSIDERATIONS:");
        System.out.println("   - 1B users, 500M daily actives, 100K posts/second");
        System.out.println("   - Timeline cache: Redis cluster (sharded by userId)");
        System.out.println("   - Post store: Cassandra (partitioned by authorId)");
        System.out.println("   - Social graph: TAO-like graph database");
        System.out.println("   - Fan-out workers: Kafka consumers (async, decoupled)");
        System.out.println("   - Ranking: ML model serving (TensorFlow Serving / custom)");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("   End of News Feed System Demo");
        System.out.println(SEPARATOR);
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private static void printSection(String title) {
        System.out.println(SEPARATOR);
        System.out.println("  " + title);
        System.out.println(SEPARATOR);
        System.out.println();
    }

    private static void printPage(List<FeedItem> page, int pageNumber) {
        if (page.isEmpty()) {
            System.out.printf("   Page %d: (empty — end of feed)%n", pageNumber);
        } else {
            for (FeedItem item : page) {
                System.out.printf("   Page %d, Item: [score=%.4f] %s: '%.40s'%n",
                        pageNumber, item.getScore(),
                        item.getPost().getAuthorName(),
                        item.getPost().getContent());
            }
        }
        System.out.println();
    }
}
