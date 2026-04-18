package com.systemdesign.feed.ranking;

import com.systemdesign.feed.model.FeedItem;

import java.util.List;

public interface FeedRanker {

    List<FeedItem> rank(List<FeedItem> items);

    String name();
}
