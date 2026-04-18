package com.systemdesign.feed.model;

public enum UserType {
    NORMAL("Normal User"),
    CELEBRITY("Celebrity"),
    VERIFIED("Verified User");

    private final String label;

    UserType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
