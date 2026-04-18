package com.systemdesign.chat.repository;

import com.systemdesign.chat.model.Conversation;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for conversations.
 */
public interface ConversationRepository {

    void save(Conversation conversation);

    Optional<Conversation> findById(String conversationId);

    /**
     * Returns all conversations that the given user is a member of.
     */
    List<Conversation> findByUserId(String userId);

    /**
     * Finds an existing 1:1 conversation between two users, if one exists.
     */
    Optional<Conversation> findOneToOne(String userA, String userB);
}
