package com.systemdesign.feed.exception;

public class TweetNotFoundException extends FeedException {

    public TweetNotFoundException(String tweetId) {
        super("Tweet not found: " + tweetId);
    }
}
