package com.systemdesign.feed.ranking;

import com.systemdesign.feed.model.FeedItem;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks feed items purely by time -- newest first.
 * Score is timestamp-based freshness (newer = higher score).
 */
public class ChronologicalRanker implements FeedRanker {

    @Override
    public List<FeedItem> rank(List<FeedItem> items) {
        List<FeedItem> ranked = new ArrayList<>(items);
        LocalDateTime now = LocalDateTime.now();

        for (FeedItem item : ranked) {
            long secondsAgo = Duration.between(item.getTweet().getCreatedAt(), now).getSeconds();
            double freshnessScore = 1000.0 / (1.0 + secondsAgo * 0.01);
            item.setScore(freshnessScore);
        }

        ranked.sort(Comparator.comparing(FeedItem::getScore).reversed());
        return ranked;
    }

    @Override
    public String name() {
        return "Chronological";
    }
}
