package com.systemdesign.feed.model;

public enum FeedSource {
    FANOUT_WRITE("pre-computed"),
    FANOUT_READ("pulled at read-time"),
    MERGED("merged");

    private final String description;

    FeedSource(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return name() + " (" + description + ")";
    }
}
