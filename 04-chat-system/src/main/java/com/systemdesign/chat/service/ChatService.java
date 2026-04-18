package com.systemdesign.chat.service;

import com.systemdesign.chat.model.*;
import com.systemdesign.chat.repository.ConversationRepository;
import com.systemdesign.chat.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrator / Mediator that ties together all chat subsystems.
 * Provides a high-level API for the controller layer, hiding the
 * complexity of message routing, presence, and group management.
 */
public class ChatService {

    private final MessageService msgService;
    private final GroupService groupService;
    private final PresenceService presenceService;
    private final MessageRouter router;
    private final ConversationRepository convRepo;
    private final UserRepository userRepo;

    public ChatService(MessageService msgService, GroupService groupService,
                       PresenceService presenceService, MessageRouter router,
                       ConversationRepository convRepo, UserRepository userRepo) {
        this.msgService = msgService;
        this.groupService = groupService;
        this.presenceService = presenceService;
        this.router = router;
        this.convRepo = convRepo;
        this.userRepo = userRepo;
    }

    /**
     * Sends a direct (1:1) message. Creates the conversation if one does not
     * already exist between the two users.
     */
    public Message sendDirectMessage(String senderId, String recipientId, String content,
                                      MessageType type) {
        // Find or create the 1:1 conversation
        Conversation conv = convRepo.findOneToOne(senderId, recipientId)
                .orElseGet(() -> {
                    String convId = UUID.randomUUID().toString().substring(0, 8);
                    Conversation newConv = new Conversation(convId, ConversationType.ONE_TO_ONE,
                            senderId + " & " + recipientId, senderId);
                    newConv.addMember(senderId);
                    newConv.addMember(recipientId);
                    convRepo.save(newConv);
                    System.out.printf("  [CONV] Created 1:1 conversation %s between %s and %s%n",
                            convId, senderId, recipientId);
                    return newConv;
                });

        return msgService.sendMessage(senderId, conv.getConversationId(), content, type);
    }

    /**
     * Sends a message to a group conversation.
     */
    public Message sendGroupMessage(String senderId, String groupId, String content,
                                     MessageType type) {
        return msgService.sendMessage(senderId, groupId, content, type);
    }

    /**
     * Creates a new group chat.
     */
    public GroupChat createGroup(String name, String creatorId, List<String> memberIds,
                                 String description) {
        return groupService.createGroup(name, creatorId, memberIds, description);
    }

    /**
     * Handles a user coming online: updates presence and delivers queued messages.
     */
    public void userConnects(String userId) {
        presenceService.heartbeat(userId, "server-1");
        userRepo.updateStatus(userId, UserStatus.ONLINE);
        router.deliverOfflineMessages(userId);
    }

    /**
     * Handles a user going offline.
     */
    public void userDisconnects(String userId) {
        presenceService.disconnect(userId);
        userRepo.updateStatus(userId, UserStatus.OFFLINE);
    }

    public List<Message> getConversationHistory(String conversationId, int limit) {
        return msgService.getHistory(conversationId, limit, Long.MAX_VALUE);
    }

    public List<Conversation> getUserConversations(String userId) {
        return convRepo.findByUserId(userId);
    }

    public ReadReceipt markAsRead(String messageId, String userId) {
        return msgService.markAsRead(messageId, userId);
    }

    public Message addMemberToGroup(String groupId, String userId, String addedBy) {
        return groupService.addMember(groupId, userId, addedBy);
    }

    public Message removeMemberFromGroup(String groupId, String userId, String removedBy) {
        return groupService.removeMember(groupId, userId, removedBy);
    }

    public PresenceService getPresenceService() {
        return presenceService;
    }

    public GroupService getGroupService() {
        return groupService;
    }
}
