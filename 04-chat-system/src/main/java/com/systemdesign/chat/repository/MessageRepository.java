package com.systemdesign.chat.repository;

import com.systemdesign.chat.model.Message;
import com.systemdesign.chat.model.MessageStatus;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for messages.
 * Supports sequence-based pagination for conversation history retrieval.
 */
public interface MessageRepository {

    void save(Message message);

    Optional<Message> findById(String messageId);

    /**
     * Returns messages for a conversation ordered by sequence number descending,
     * only including messages with sequence numbers strictly less than {@code beforeSequence}.
     *
     * @param conversationId the conversation to query
     * @param limit          maximum number of messages to return
     * @param beforeSequence only return messages before this sequence number
     * @return ordered list of messages (newest first)
     */
    List<Message> findByConversationId(String conversationId, int limit, long beforeSequence);

    void updateDeliveryStatus(String messageId, String userId, MessageStatus status);
}
