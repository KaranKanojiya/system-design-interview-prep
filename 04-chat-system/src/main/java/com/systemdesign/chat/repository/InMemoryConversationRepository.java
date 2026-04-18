package com.systemdesign.chat.repository;

import com.systemdesign.chat.model.Conversation;
import com.systemdesign.chat.model.ConversationType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory conversation store.
 * Supports lookup by ID, by user membership, and 1:1 deduplication.
 */
public class InMemoryConversationRepository implements ConversationRepository {

    private final ConcurrentHashMap<String, Conversation> conversations = new ConcurrentHashMap<>();

    @Override
    public void save(Conversation conversation) {
        conversations.put(conversation.getConversationId(), conversation);
    }

    @Override
    public Optional<Conversation> findById(String conversationId) {
        return Optional.ofNullable(conversations.get(conversationId));
    }

    @Override
    public List<Conversation> findByUserId(String userId) {
        return conversations.values().stream()
                .filter(c -> c.isMember(userId))
                .toList();
    }

    @Override
    public Optional<Conversation> findOneToOne(String userA, String userB) {
        return conversations.values().stream()
                .filter(c -> c.getType() == ConversationType.ONE_TO_ONE)
                .filter(c -> c.isMember(userA) && c.isMember(userB))
                .findFirst();
    }
}
