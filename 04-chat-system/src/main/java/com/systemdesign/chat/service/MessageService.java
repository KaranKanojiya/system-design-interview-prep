package com.systemdesign.chat.service;

import com.systemdesign.chat.exception.ConversationNotFoundException;
import com.systemdesign.chat.exception.UnauthorizedException;
import com.systemdesign.chat.model.*;
import com.systemdesign.chat.repository.ConversationRepository;
import com.systemdesign.chat.repository.MessageRepository;
import com.systemdesign.chat.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/**
 * Core message operations: sending, history retrieval, and read receipts.
 * Coordinates between the repository layer and the message router.
 */
public class MessageService {

    private final MessageRepository msgRepo;
    private final ConversationRepository convRepo;
    private final MessageRouter router;
    private final UserRepository userRepo;

    public MessageService(MessageRepository msgRepo, ConversationRepository convRepo,
                          MessageRouter router, UserRepository userRepo) {
        this.msgRepo = msgRepo;
        this.convRepo = convRepo;
        this.router = router;
        this.userRepo = userRepo;
    }

    /**
     * Sends a message within a conversation. Validates membership, assigns
     * a sequence number, persists, and routes to recipients.
     */
    public Message sendMessage(String senderId, String conversationId, String content,
                               MessageType type) {
        Conversation conv = convRepo.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        if (!conv.isMember(senderId)) {
            throw new UnauthorizedException(senderId + " is not a member of conversation " + conversationId);
        }

        // Build the message
        long seq = conv.nextSequence();
        Message.Builder builder = new Message.Builder()
                .messageId(UUID.randomUUID().toString().substring(0, 8))
                .conversationId(conversationId)
                .senderId(senderId)
                .content(content)
                .type(type)
                .status(MessageStatus.SENT)
                .sequenceNumber(seq);

        // Initialize per-recipient delivery status for all members except sender
        for (String memberId : conv.getMemberIds()) {
            if (!memberId.equals(senderId)) {
                builder.addRecipient(memberId);
            }
        }

        Message message = builder.build();

        // Persist
        msgRepo.save(message);

        // Resolve sender name for display
        String senderName = userRepo.findById(senderId)
                .map(User::getUsername)
                .orElse(senderId);

        // Route based on conversation type
        if (conv.getType() == ConversationType.ONE_TO_ONE) {
            String recipientId = conv.getOtherMember(senderId);
            router.routeToUser(message, recipientId, senderName);
        } else {
            router.routeToGroup(message, conv, senderName);
        }

        return message;
    }

    /**
     * Retrieves conversation history with cursor-based pagination.
     */
    public List<Message> getHistory(String conversationId, int limit, long beforeSequence) {
        return msgRepo.findByConversationId(conversationId, limit, beforeSequence);
    }

    /**
     * Marks a message as read by a user and notifies the sender via read receipt.
     */
    public ReadReceipt markAsRead(String messageId, String userId) {
        Message message = msgRepo.findById(messageId)
                .orElseThrow(() -> new ConversationNotFoundException("Message not found: " + messageId));

        msgRepo.updateDeliveryStatus(messageId, userId, MessageStatus.READ);

        ReadReceipt receipt = new ReadReceipt(messageId, userId, MessageStatus.READ);

        // Notify the original sender about the read receipt
        String senderName = userRepo.findById(userId)
                .map(User::getUsername)
                .orElse(userId);

        System.out.printf("  [READ] %s read message %s%n", senderName, messageId);

        return receipt;
    }
}
