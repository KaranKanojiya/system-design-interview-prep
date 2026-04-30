package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.exception.UserNotFoundException;
import com.systemdesign.newsfeed.model.FeedCursor;
import com.systemdesign.newsfeed.model.FeedItem;
import com.systemdesign.newsfeed.model.FeedItem.FeedItemSource;
import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.PostRepository;
import com.systemdesign.newsfeed.repository.UserRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FeedService — FACADE: the main entry point for reading a user's feed.
 *
 * Design notes for interview:
 * - This is the READ PATH. The most complex piece of the news feed system.
 * - getFeed() assembles a feed from multiple sources, deduplicates, ranks, and paginates.
 *
 * THE FEED ASSEMBLY PIPELINE:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  1. TIMELINE CACHE (pre-computed via fan-out-on-write)             │
 * │     -> Posts from NORMAL users you follow                         │
 * │     -> Already in your timeline cache (fast O(1) read)            │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  2. CELEBRITY PULL (fan-out-on-read)                               │
 * │     -> Posts from CELEBRITY users you follow                      │
 * │     -> Pulled at read time from each celebrity's post list         │
 * │     -> O(C) where C = number of celebrities you follow            │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  3. MERGE + DEDUPLICATE                                            │
 * │     -> Combine both sources into one list                         │
 * │     -> Remove duplicates (same postId from multiple sources)      │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  4. RANK                                                           │
 * │     -> Apply ranking strategy (algorithmic or chronological)      │
 * │     -> Each item gets a score; list is sorted by score DESC       │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  5. PAGINATE                                                       │
 * │     -> Apply cursor-based pagination                              │
 * │     -> Return pageSize items starting from cursor position        │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Call chain:
 *   FeedController.handleGetFeed(userId, cursor)
 *     -> FeedService.getFeed(userId, cursor)
 *       -> TimelineService.getTimeline(userId, cursor)   [step 1: cached timeline]
 *       -> SocialGraphService.getFollowing(userId)       [step 2: who do I follow?]
 *       -> PostRepository.findRecentByAuthorId(celeb)    [step 2: pull celebrity posts]
 *       -> deduplication logic                           [step 3]
 *       -> RankingService.rank(items, viewer)            [step 4]
 *       -> pagination logic                              [step 5]
 */
public class FeedService {

    private final TimelineService timelineService;
    private final SocialGraphService socialGraphService;
    private final RankingService rankingService;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    // --- Performance stats ---
    private long totalFeedGenTimeMs = 0;
    private int totalFeedGenCount = 0;

    // How many recent posts to pull per celebrity on read
    private static final int CELEBRITY_PULL_LIMIT = 10;

    public FeedService(TimelineService timelineService,
                       SocialGraphService socialGraphService,
                       RankingService rankingService,
                       PostRepository postRepository,
                       UserRepository userRepository) {
        this.timelineService = timelineService;
        this.socialGraphService = socialGraphService;
        this.rankingService = rankingService;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
    }

    /**
     * Get a user's feed — the main read path.
     *
     * @param userId the user requesting their feed
     * @param cursor pagination cursor (firstPage() for initial load)
     * @return ranked, paginated list of FeedItems
     */
    public List<FeedItem> getFeed(String userId, FeedCursor cursor) {
        long startTime = System.nanoTime();

        User viewer = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // ============================================================
        // STEP 1: Get pre-computed timeline from cache
        // ============================================================
        // These are posts from NORMAL users the viewer follows.
        // They were pushed to the viewer's timeline during fan-out-on-write.
        // This is O(1) — just a cache read.
        List<Post> timelinePosts = timelineService.getTimeline(userId, cursor);
        List<FeedItem> allItems = new ArrayList<>();

        for (Post post : timelinePosts) {
            allItems.add(new FeedItem(post, 0.0, FeedItemSource.TIMELINE_CACHE));
        }

        // ============================================================
        // STEP 2: Pull celebrity posts (fan-out-on-read)
        // ============================================================
        // For each CELEBRITY the viewer follows, pull their recent posts.
        // These posts were NOT pushed to the viewer's timeline (to avoid
        // write amplification), so we fetch them now at read time.
        // This is O(C * K) where C = celebrities followed, K = posts per celebrity.
        List<String> followingIds = socialGraphService.getFollowing(userId);

        for (String followingId : followingIds) {
            User followedUser = userRepository.findById(followingId).orElse(null);
            if (followedUser != null && followedUser.isCelebrity()) {
                // Pull recent posts from this celebrity
                List<Post> celebrityPosts = postRepository.findRecentByAuthorId(
                        followingId, CELEBRITY_PULL_LIMIT);
                for (Post post : celebrityPosts) {
                    allItems.add(new FeedItem(post, 0.0, FeedItemSource.PULLED_ON_READ));
                }
            }
        }

        // ============================================================
        // STEP 3: Deduplicate
        // ============================================================
        // A post might appear in both the timeline cache AND the celebrity pull
        // (edge case: a user crosses the celebrity threshold between write and read).
        // We deduplicate by postId, keeping the first occurrence.
        Set<String> seenPostIds = new HashSet<>();
        List<FeedItem> deduplicated = new ArrayList<>();
        for (FeedItem item : allItems) {
            if (seenPostIds.add(item.getPost().getPostId())) {
                deduplicated.add(item);
            }
        }

        // ============================================================
        // STEP 4: Rank
        // ============================================================
        // Apply the configured ranking strategy (algorithmic or chronological).
        // This computes a score for each item and sorts by score DESC.
        List<FeedItem> ranked = rankingService.rank(deduplicated, viewer);

        // ============================================================
        // STEP 5: Paginate
        // ============================================================
        // Apply cursor-based pagination to return only the requested page.
        // For the first page, return the top N items.
        // For subsequent pages, skip items until we pass the cursor, then take N.
        List<FeedItem> paginated = applyPagination(ranked, cursor);

        // --- Performance tracking ---
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        totalFeedGenTimeMs += elapsedMs;
        totalFeedGenCount++;

        return paginated;
    }

    /**
     * Apply cursor-based pagination to the ranked feed items.
     *
     * For first page: return top pageSize items.
     * For subsequent pages: skip items until past cursor, then return pageSize items.
     */
    private List<FeedItem> applyPagination(List<FeedItem> ranked, FeedCursor cursor) {
        if (cursor.isFirstPage()) {
            // First page: just take the top N
            int end = Math.min(cursor.getPageSize(), ranked.size());
            return ranked.subList(0, end);
        }

        // Subsequent pages: find the cursor position and take the next N
        boolean pastCursor = false;
        List<FeedItem> page = new ArrayList<>();

        for (FeedItem item : ranked) {
            if (pastCursor) {
                page.add(item);
                if (page.size() >= cursor.getPageSize()) {
                    break;
                }
            } else if (item.getPost().getPostId().equals(cursor.getLastPostId())) {
                pastCursor = true;
                // Don't include the cursor item itself (it was the last item of the previous page)
            }
        }

        return page;
    }

    // --- Stats ---

    public long getTotalFeedGenTimeMs() {
        return totalFeedGenTimeMs;
    }

    public int getTotalFeedGenCount() {
        return totalFeedGenCount;
    }

    public double getAverageFeedGenTimeMs() {
        return totalFeedGenCount == 0 ? 0 : (double) totalFeedGenTimeMs / totalFeedGenCount;
    }
}
