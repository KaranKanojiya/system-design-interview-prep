package com.systemdesign.chat.repository;

import com.systemdesign.chat.model.Message;
import com.systemdesign.chat.model.MessageStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory message store with dual indexing:
 * primary by messageId, secondary by conversationId for fast history queries.
 */
public class InMemoryMessageRepository implements MessageRepository {

    private final ConcurrentHashMap<String, Message> messagesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Message>> messagesByConversation = new ConcurrentHashMap<>();

    @Override
    public void save(Message message) {
        messagesById.put(message.getMessageId(), message);
        messagesByConversation
                .computeIfAbsent(message.getConversationId(), k -> new CopyOnWriteArrayList<>())
                .add(message);
    }

    @Override
    public Optional<Message> findById(String messageId) {
        return Optional.ofNullable(messagesById.get(messageId));
    }

    @Override
    public List<Message> findByConversationId(String conversationId, int limit, long beforeSequence) {
        List<Message> convMessages = messagesByConversation.getOrDefault(conversationId, List.of());

        return convMessages.stream()
                .filter(m -> m.getSequenceNumber() < beforeSequence)
                .sorted(Comparator.comparingLong(Message::getSequenceNumber).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void updateDeliveryStatus(String messageId, String userId, MessageStatus status) {
        Message message = messagesById.get(messageId);
        if (message != null) {
            if (status == MessageStatus.READ) {
                message.markAsRead(userId);
            } else if (status == MessageStatus.DELIVERED) {
                message.markAsDelivered(userId);
            }
        }
    }
}
