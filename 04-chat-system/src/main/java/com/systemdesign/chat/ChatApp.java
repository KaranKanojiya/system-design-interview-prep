package com.systemdesign.chat;

import com.systemdesign.chat.config.AppConfig;
import com.systemdesign.chat.controller.ChatController;
import com.systemdesign.chat.model.*;

import java.util.List;

/**
 * Main demonstration application showcasing all features of the chat system.
 * Runs 7 demo scenarios that exercise 1:1 chat, offline delivery, group chat,
 * read receipts, presence, group management, and message history.
 */
public class ChatApp {

    private static final String SEPARATOR = "─".repeat(60);

    public static void main(String[] args) {
        System.out.println("=== Chat System — System Design Demo ===");
        System.out.println(SEPARATOR);

        // --- Bootstrap ---
        AppConfig config = new AppConfig();
        config.seedUsers();
        ChatController controller = config.createController();
        System.out.println(SEPARATOR);

        // ╔══════════════════════════════════════════╗
        // ║  Demo 1: 1:1 Chat (Real-time Delivery)  ║
        // ╚══════════════════════════════════════════╝
        System.out.println("\n[Demo 1] 1:1 Chat — Real-time Delivery");
        System.out.println(SEPARATOR);

        // Both Alice and Bob are online
        config.setupConnections("alice", "bob");
        controller.handleConnect("alice");
        controller.handleConnect("bob");

        Message msg1 = controller.handleSendDirectMessage("alice", "bob", "Hey Bob!");
        Message msg2 = controller.handleSendDirectMessage("bob", "alice", "Hey Alice! What's up?");
        Message msg3 = controller.handleSendDirectMessage("alice", "bob", "Want to grab lunch?");

        System.out.println(SEPARATOR);

        // ╔══════════════════════════════════════════╗
        // ║  Demo 2: Offline Message Delivery        ║
        // ╚══════════════════════════════════════════╝
        System.out.println("\n[Demo 2] Offline Message Delivery");
        System.out.println(SEPARATOR);

        // Carol is offline
        System.out.println("  Carol is currently OFFLINE");
        Message offlineMsg = controller.handleSendDirectMessage("alice", "carol",
                "Carol, are you there?");
        System.out.printf("  Offline queue for carol: %d message(s)%n",
                config.getRouter().getOfflineQueueSize("carol"));

        // Carol comes online
        System.out.println("\n  --- Carol comes online ---");
        config.setupConnections("carol");
        controller.handleConnect("carol");

        System.out.println(SEPARATOR);

        // ╔══════════════════════════════════════════╗
        // ║  Demo 3: Group Chat with Fan-out         ║
        // ╚══════════════════════════════════════════╝
        System.out.println("\n[Demo 3] Group Chat — Fan-out Delivery");
        System.out.println(SEPARATOR);

        config.setupConnections("dave");
        controller.handleConnect("dave");

        GroupChat projectGroup = controller.handleCreateGroup(
                "Project Team", "alice",
                List.of("alice", "bob", "carol", "dave"),
                "Engineering team standup group");

        String groupId = projectGroup.getConversationId();

        Message groupMsg1 = controller.handleSendGroupMessage("alice", groupId,
                "Team standup at 10am");
        Message groupMsg2 = controller.handleSendGroupMessage("dave", groupId,
                "I'll be 5 min late");

        System.out.println(SEPARATOR);

        // ╔══════════════════════════════════════════╗
        // ║  Demo 4: Read Receipts                   ║
        // ╚══════════════════════════════════════════╝
        System.out.println("\n[Demo 4] Read Receipts — Tick Progression");
        System.out.println(SEPARATOR);

        // Show the progression: SENT -> DELIVERED -> READ
        System.out.printf("  Message '%s' status: %s (after send)%n",
                msg1.getContent(), msg1.getStatus().getSymbol());

        // Bob reads Alice's first message
        ReadReceipt receipt = controller.handleReadReceipt(msg1.getMessageId(), "bob");
        System.out.printf("  Message '%s' status: %s (after read)%n",
                msg1.getContent(), msg1.getStatus().getSymbol());
        System.out.printf("  Bob's delivery status: %s%n",
                msg1.getDeliveryStatusForUser("bob").getSymbol());

        System.out.println(SEPARATOR);

        // ╔══════════════════════════════════════════╗
        // ║  Demo 5: Presence                        ║
        // ╚══════════════════════════════════════════╝
        System.out.println("\n[Demo 5] Presence — Online/Offline Indicators");
        System.out.println(SEPARATOR);

        // Show current presence
        PresenceInfo alicePresence = config.getChatService().getPresenceService()
                .getPresence("alice");
        System.out.printf("  Alice: %s %s%n",
                alicePresence.getStatus().getIndicator(), alicePresence.getStatus());

        // Eve connects
        config.setupConnections("eve");
        controller.handleConnect("eve");
        PresenceInfo evePresence = config.getChatService().getPresenceService()
                .getPresence("eve");
        System.out.printf("  Eve: %s %s%n",
                evePresence.getStatus().getIndicator(), evePresence.getStatus());

        // Eve disconnects
        controller.handleDisconnect("eve");
        evePresence = config.getChatService().getPresenceService().getPresence("eve");
        System.out.printf("  Eve: %s %s (last seen: %s)%n",
                evePresence.getStatus().getIndicator(), evePresence.getStatus(),
                evePresence.getLastHeartbeat());

        // Show online user count
        List<String> onlineUsers = config.getChatService().getPresenceService().getOnlineUsers();
        System.out.printf("  Online users: %s%n", onlineUsers);

        System.out.println(SEPARATOR);

        // ╔══════════════════════════════════════════╗
        // ║  Demo 6: Group Management                ║
        // ╚══════════════════════════════════════════╝
        System.out.println("\n[Demo 6] Group Management — Admin Operations");
        System.out.println(SEPARATOR);

        // Alice (admin) adds Eve to the group
        config.setupConnections("eve");
        controller.handleConnect("eve");

        Message addMsg = controller.handleAddMember(groupId, "eve", "alice");
        System.out.printf("  System: %s%n", addMsg.getContent());

        // Alice removes Dave from the group
        Message removeMsg = controller.handleRemoveMember(groupId, "dave", "alice");
        System.out.printf("  System: %s%n", removeMsg.getContent());

        // Show updated group info
        GroupChat updatedGroup = config.getChatService().getGroupService().getGroupInfo(groupId);
        System.out.printf("  Group '%s' now has %d members: %s%n",
                updatedGroup.getName(), updatedGroup.getMemberCount(), updatedGroup.getMemberIds());

        System.out.println(SEPARATOR);

        // ╔══════════════════════════════════════════╗
        // ║  Demo 7: Message History (Pagination)    ║
        // ╚══════════════════════════════════════════╝
        System.out.println("\n[Demo 7] Message History — Paginated Retrieval");
        System.out.println(SEPARATOR);

        // Get the 1:1 conversation between Alice and Bob
        List<Conversation> aliceConvs = config.getChatService().getUserConversations("alice");
        System.out.printf("  Alice has %d conversation(s)%n", aliceConvs.size());

        // Retrieve last 5 messages from the group
        List<Message> groupHistory = controller.handleGetHistory(groupId, 5);
        System.out.println("  Group message history (newest first):");
        for (Message m : groupHistory) {
            System.out.printf("    seq=%d | %s: %s %s%n",
                    m.getSequenceNumber(), m.getSenderId(), m.getContent(),
                    m.getStatus().getSymbol());
        }

        // Retrieve 1:1 history
        if (!aliceConvs.isEmpty()) {
            Conversation firstConv = aliceConvs.stream()
                    .filter(c -> c.getType() == ConversationType.ONE_TO_ONE)
                    .findFirst().orElse(null);
            if (firstConv != null) {
                List<Message> dmHistory = controller.handleGetHistory(
                        firstConv.getConversationId(), 5);
                System.out.println("\n  1:1 message history (newest first):");
                for (Message m : dmHistory) {
                    System.out.printf("    seq=%d | %s: %s %s%n",
                            m.getSequenceNumber(), m.getSenderId(), m.getContent(),
                            m.getStatus().getSymbol());
                }
            }
        }

        System.out.println(SEPARATOR);

        // ╔══════════════════════════════════════════╗
        // ║  Design Summary                          ║
        // ╚══════════════════════════════════════════╝
        System.out.println("\n=== Design Summary ===");
        System.out.println("""
                Architecture Layers:
                  1. Model       — User, Message (Builder), Conversation, GroupChat, enums
                  2. Repository   — Interface + InMemory (ConcurrentHashMap-backed)
                  3. Handler      — ConnectionRegistry + ConnectionHandler (simulated WebSocket)
                  4. Service      — PresenceService, MessageRouter, GroupService, MessageService
                  5. Orchestrator — ChatService (Mediator pattern)
                  6. Controller   — ChatController (simulated REST API)

                Key Design Patterns:
                  - Builder          : Message construction with fluent API
                  - Mediator         : ChatService orchestrates subsystems
                  - Repository       : Data access abstraction (swappable backends)
                  - Observer (sim.)  : ConnectionHandler delivers real-time events
                  - Strategy         : MessageRouter online vs. offline delivery

                Concurrency:
                  - ConcurrentHashMap for all stores
                  - CopyOnWriteArrayList for member lists
                  - ConcurrentSkipListSet for admin sets
                  - AtomicLong for sequence counters

                Scalability Considerations:
                  - Sequence-based pagination for message history
                  - Per-recipient delivery tracking for group messages
                  - Heartbeat-based presence with timeout detection
                  - Offline message queuing for disconnected users
                  - Connection registry abstraction for multi-server routing
                """);
        System.out.println("=== Demo Complete ===");
    }
}
