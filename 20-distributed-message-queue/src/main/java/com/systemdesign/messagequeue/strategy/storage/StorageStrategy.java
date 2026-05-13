package com.systemdesign.messagequeue.strategy.storage;

import com.systemdesign.messagequeue.model.Message;

import java.util.List;

// Strategy Pattern (GoF) — determines how messages are stored/compacted
public interface StorageStrategy {

    /**
     * Decides whether a message should be retained based on the retention window.
     *
     * @param message     the message to evaluate
     * @param retentionMs retention window in milliseconds
     * @return true if the message should be kept
     */
    boolean shouldRetain(Message message, long retentionMs);

    /**
     * Compacts or cleans the given list of messages according to this strategy.
     *
     * @param messages the messages to compact
     * @return a new list containing only the retained messages
     */
    List<Message> compact(List<Message> messages);

    /** Returns a human-readable name for this strategy. */
    String getStrategyName();
}
