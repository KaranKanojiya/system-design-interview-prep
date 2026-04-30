package com.systemdesign.newsfeed.strategy.fanout;

import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.store.TimelineCache;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HybridFanoutStrategy — THE COMPOSITE: best of both worlds.
 *
 * Design notes for interview:
 * - This is what Facebook, Instagram, and LinkedIn actually use.
 * - Decision logic:
 *   If author is a celebrity (followerCount > 10,000):
 *     -> Use fan-out on READ (no-op on publish, pull at read time)
 *     -> Reason: pushing to millions of timelines is too expensive
 *   Else (normal user):
 *     -> Use fan-out on WRITE (push to all follower timelines)
 *     -> Reason: follower count is small, so push cost is bounded
 *
 * - The threshold (10,000) is a tunable parameter. In production, it might be:
 *   - Higher (100K) if your infrastructure handles writes well
 *   - Dynamic (based on current system load)
 *   - Per-user (some accounts opted into a "creator" tier)
 *
 * - The beauty of this pattern: from the CALLER's perspective, it's just one
 *   strategy. PostService doesn't know or care about the routing logic.
 *   This is the Strategy Pattern + Composite Pattern in action.
 *
 * Call chain:
 *   PostService.createPost()
 *     -> FanoutService.distribute()
 *       -> HybridFanoutStrategy.distribute()
 *         -> IF celebrity: FanoutOnReadStrategy.distribute()  (no-op)
 *         -> ELSE:         FanoutOnWriteStrategy.distribute() (push to all)
 */
public class HybridFanoutStrategy implements FanoutStrategy {

    private final FanoutOnWriteStrategy writeStrategy;
    private final FanoutOnReadStrategy readStrategy;

    // --- Stats for monitoring ---
    // Track how many posts go through each path for observability.
    private final AtomicInteger writePathCount = new AtomicInteger(0);
    private final AtomicInteger readPathCount = new AtomicInteger(0);

    public HybridFanoutStrategy(FanoutOnWriteStrategy writeStrategy,
                                 FanoutOnReadStrategy readStrategy) {
        this.writeStrategy = writeStrategy;
        this.readStrategy = readStrategy;
    }

    @Override
    public void distribute(Post post, User author, List<String> followerIds, TimelineCache cache) {
        // --- THE ROUTING DECISION ---
        // This single if-statement is the crux of the hybrid fan-out design.
        if (author.isCelebrity()) {
            // Celebrity path: fan-out on read (no-op)
            // Post is saved in PostRepository; followers will pull it at read time.
            readPathCount.incrementAndGet();
            readStrategy.distribute(post, author, followerIds, cache);
        } else {
            // Normal user path: fan-out on write (push to all followers)
            // Each follower's timeline cache gets this postId.
            writePathCount.incrementAndGet();
            writeStrategy.distribute(post, author, followerIds, cache);
        }
    }

    @Override
    public String getStrategyName() {
        return "Hybrid Fan-out (Write for normal, Read for celebrities)";
    }

    // --- Stats getters for monitoring/display ---

    public int getWritePathCount() {
        return writePathCount.get();
    }

    public int getReadPathCount() {
        return readPathCount.get();
    }

    public String getStats() {
        return String.format("HybridFanout{writePath=%d, readPath=%d, total=%d}",
                writePathCount.get(), readPathCount.get(),
                writePathCount.get() + readPathCount.get());
    }
}
