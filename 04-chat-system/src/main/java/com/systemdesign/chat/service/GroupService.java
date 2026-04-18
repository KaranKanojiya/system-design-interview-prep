package com.systemdesign.chat.service;

import com.systemdesign.chat.exception.ConversationNotFoundException;
import com.systemdesign.chat.exception.UnauthorizedException;
import com.systemdesign.chat.exception.UserNotFoundException;
import com.systemdesign.chat.model.GroupChat;
import com.systemdesign.chat.model.Message;
import com.systemdesign.chat.model.MessageStatus;
import com.systemdesign.chat.model.MessageType;
import com.systemdesign.chat.repository.ConversationRepository;
import com.systemdesign.chat.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/**
 * Handles group lifecycle operations: creation, membership changes,
 * and admin management. Generates system messages for auditable group events.
 */
public class GroupService {

    private final ConversationRepository convRepo;
    private final UserRepository userRepo;

    public GroupService(ConversationRepository convRepo, UserRepository userRepo) {
        this.convRepo = convRepo;
        this.userRepo = userRepo;
    }

    /**
     * Creates a new group chat with the creator as the first admin and member.
     */
    public GroupChat createGroup(String name, String creatorId, List<String> memberIds,
                                 String description) {
        userRepo.findById(creatorId)
                .orElseThrow(() -> new UserNotFoundException(creatorId));

        String groupId = UUID.randomUUID().toString().substring(0, 8);
        GroupChat group = new GroupChat(groupId, name, creatorId, description);

        // Creator is both admin and member
        group.addAdmin(creatorId);
        group.addMember(creatorId);

        // Add other members
        for (String memberId : memberIds) {
            if (!memberId.equals(creatorId)) {
                group.addMember(memberId);
            }
        }

        convRepo.save(group);
        System.out.printf("  [GROUP] Created group '%s' (%s) with %d members%n",
                name, groupId, group.getMemberCount());
        return group;
    }

    /**
     * Adds a member to a group. Only admins can add members.
     * Returns a system message describing the action.
     */
    public Message addMember(String groupId, String userId, String addedBy) {
        GroupChat group = getGroupChat(groupId);
        validateAdmin(group, addedBy);

        group.addMember(userId);
        System.out.printf("  [GROUP] %s added %s to '%s'%n", addedBy, userId, group.getName());

        return buildSystemMessage(groupId, group,
                addedBy + " added " + userId + " to the group");
    }

    /**
     * Removes a member from a group. Only admins can remove members.
     */
    public Message removeMember(String groupId, String userId, String removedBy) {
        GroupChat group = getGroupChat(groupId);
        validateAdmin(group, removedBy);

        group.removeMember(userId);
        group.removeAdmin(userId); // also strip admin if they had it
        System.out.printf("  [GROUP] %s removed %s from '%s'%n", removedBy, userId, group.getName());

        return buildSystemMessage(groupId, group,
                removedBy + " removed " + userId + " from the group");
    }

    /**
     * A user voluntarily leaves a group.
     * If they were the only admin, the next member is promoted.
     */
    public Message leaveGroup(String groupId, String userId) {
        GroupChat group = getGroupChat(groupId);

        group.removeMember(userId);

        // If leaving user was admin and no other admins remain, promote next member
        if (group.isAdmin(userId)) {
            group.removeAdmin(userId);
            if (group.getAdminIds().isEmpty() && group.getMemberCount() > 0) {
                String nextAdmin = group.getMemberIds().get(0);
                group.addAdmin(nextAdmin);
                System.out.printf("  [GROUP] %s promoted to admin in '%s'%n",
                        nextAdmin, group.getName());
            }
        }

        System.out.printf("  [GROUP] %s left '%s'%n", userId, group.getName());
        return buildSystemMessage(groupId, group, userId + " left the group");
    }

    public GroupChat getGroupInfo(String groupId) {
        return getGroupChat(groupId);
    }

    // --- Private helpers ---

    private GroupChat getGroupChat(String groupId) {
        return convRepo.findById(groupId)
                .filter(c -> c instanceof GroupChat)
                .map(c -> (GroupChat) c)
                .orElseThrow(() -> new ConversationNotFoundException(groupId));
    }

    private void validateAdmin(GroupChat group, String userId) {
        if (!group.isAdmin(userId)) {
            throw new UnauthorizedException(
                    userId + " is not an admin of group " + group.getName());
        }
    }

    private Message buildSystemMessage(String groupId, GroupChat group, String content) {
        return new Message.Builder()
                .messageId(UUID.randomUUID().toString().substring(0, 8))
                .conversationId(groupId)
                .senderId("system")
                .content(content)
                .type(MessageType.SYSTEM)
                .status(MessageStatus.DELIVERED)
                .sequenceNumber(group.nextSequence())
                .build();
    }
}
