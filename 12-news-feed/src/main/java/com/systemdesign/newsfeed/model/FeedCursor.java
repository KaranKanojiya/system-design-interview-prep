package com.systemdesign.newsfeed.model;

import java.time.LocalDateTime;

/**
 * FeedCursor — Cursor-based pagination for infinite scroll.
 *
 * Design notes for interview:
 * - Why cursor-based instead of offset-based pagination?
 *   1. Offset-based (LIMIT 20 OFFSET 40) breaks when new posts are inserted:
 *      user scrolls to page 3, new post appears, page 3 now shows a duplicate
 *      from page 2 because everything shifted.
 *   2. Cursor-based uses the LAST SEEN item as the anchor point. "Give me 20
 *      posts AFTER this one." New posts at the top don't affect the cursor.
 *   3. Cursor-based is also more efficient for large datasets — no need to
 *      skip N rows, just seek to the cursor position (O(log N) with an index).
 *
 * - lastPostId + lastTimestamp together form the cursor.
 *   We need BOTH because timestamps alone aren't unique (two posts at the same
 *   millisecond). PostId breaks the tie.
 *
 * - pageSize defaults to 20 (standard for social feeds: Facebook, Twitter, LinkedIn).
 */
public class FeedCursor {

    private final String lastPostId;           // null on first page
    private final LocalDateTime lastTimestamp;  // null on first page
    private final int pageSize;

    private static final int DEFAULT_PAGE_SIZE = 20;

    private FeedCursor(String lastPostId, LocalDateTime lastTimestamp, int pageSize) {
        this.lastPostId = lastPostId;
        this.lastTimestamp = lastTimestamp;
        this.pageSize = pageSize;
    }

    // --- Static factories ---

    /**
     * First page — no cursor, just fetch the top N items.
     */
    public static FeedCursor firstPage(int pageSize) {
        return new FeedCursor(null, null, pageSize);
    }

    /**
     * First page with default page size (20).
     */
    public static FeedCursor firstPage() {
        return firstPage(DEFAULT_PAGE_SIZE);
    }

    /**
     * Next page — cursor points to the last item the user saw.
     * Feed service will return items AFTER this cursor.
     */
    public static FeedCursor nextPage(String lastPostId, LocalDateTime lastTimestamp, int pageSize) {
        return new FeedCursor(lastPostId, lastTimestamp, pageSize);
    }

    // --- Query methods ---

    public boolean isFirstPage() {
        return lastPostId == null && lastTimestamp == null;
    }

    // --- Getters ---

    public String getLastPostId() {
        return lastPostId;
    }

    public LocalDateTime getLastTimestamp() {
        return lastTimestamp;
    }

    public int getPageSize() {
        return pageSize;
    }

    @Override
    public String toString() {
        if (isFirstPage()) {
            return String.format("FeedCursor{FIRST_PAGE, size=%d}", pageSize);
        }
        return String.format("FeedCursor{after='%s', afterTime=%s, size=%d}",
                lastPostId, lastTimestamp, pageSize);
    }
}
