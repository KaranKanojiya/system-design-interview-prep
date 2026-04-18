package com.systemdesign.chat.controller;

import com.systemdesign.chat.model.Message;
import com.systemdesign.chat.model.MessageType;
import com.systemdesign.chat.model.GroupChat;
import com.systemdesign.chat.model.ReadReceipt;
import com.systemdesign.chat.service.ChatService;

import java.util.List;

/**
 * Simulated REST + WebSocket controller. In production this would be
 * a Spring @RestController or similar; here it prints request/response
 * traces to demonstrate the API surface.
 */
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public void handleConnect(String userId) {
        System.out.printf("  [CONNECT] %s connecting...%n", userId);
        chatService.userConnects(userId);
    }

    public void handleDisconnect(String userId) {
        System.out.printf("  [DISCONNECT] %s%n", userId);
        chatService.userDisconnects(userId);
    }

    public Message handleSendDirectMessage(String senderId, String recipientId, String content) {
        System.out.printf("  [POST /api/messages] %s -> %s: \"%s\"%n",
                senderId, recipientId, content);
        Message msg = chatService.sendDirectMessage(senderId, recipientId, content, MessageType.TEXT);
        System.out.printf("  [RESPONSE] Message %s sent %s%n", msg.getMessageId(), msg.getStatus().getSymbol());
        return msg;
    }

    public Message handleSendGroupMessage(String senderId, String groupId, String content) {
        System.out.printf("  [POST /api/groups/%s/messages] %s: \"%s\"%n",
                groupId, senderId, content);
        Message msg = chatService.sendGroupMessage(senderId, groupId, content, MessageType.TEXT);
        System.out.printf("  [RESPONSE] Message %s sent %s%n", msg.getMessageId(), msg.getStatus().getSymbol());
        return msg;
    }

    public GroupChat handleCreateGroup(String name, String creatorId, List<String> memberIds,
                                       String description) {
        System.out.printf("  [POST /api/groups] name='%s', creator=%s, members=%s%n",
                name, creatorId, memberIds);
        GroupChat group = chatService.createGroup(name, creatorId, memberIds, description);
        System.out.printf("  [RESPONSE] Group '%s' created (%s)%n", group.getName(), group.getConversationId());
        return group;
    }

    public List<Message> handleGetHistory(String conversationId, int limit) {
        System.out.printf("  [GET /api/messages/%s?limit=%d]%n", conversationId, limit);
        List<Message> history = chatService.getConversationHistory(conversationId, limit);
        System.out.printf("  [RESPONSE] Retrieved %d messages%n", history.size());
        return history;
    }

    public ReadReceipt handleReadReceipt(String messageId, String userId) {
        System.out.printf("  [PUT /api/messages/%s/read] user=%s%n", messageId, userId);
        ReadReceipt receipt = chatService.markAsRead(messageId, userId);
        System.out.printf("  [RESPONSE] Read receipt recorded %s%n", receipt.getStatus().getSymbol());
        return receipt;
    }

    public Message handleAddMember(String groupId, String userId, String addedBy) {
        System.out.printf("  [POST /api/groups/%s/members] add %s by %s%n", groupId, userId, addedBy);
        return chatService.addMemberToGroup(groupId, userId, addedBy);
    }

    public Message handleRemoveMember(String groupId, String userId, String removedBy) {
        System.out.printf("  [DELETE /api/groups/%s/members/%s] by %s%n", groupId, userId, removedBy);
        return chatService.removeMemberFromGroup(groupId, userId, removedBy);
    }

    public ChatService getChatService() {
        return chatService;
    }
}
