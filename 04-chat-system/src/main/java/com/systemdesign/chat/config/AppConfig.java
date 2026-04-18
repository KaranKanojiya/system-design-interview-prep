package com.systemdesign.chat.config;

import com.systemdesign.chat.controller.ChatController;
import com.systemdesign.chat.handler.ConnectionHandler;
import com.systemdesign.chat.handler.ConnectionRegistry;
import com.systemdesign.chat.handler.InMemoryConnectionRegistry;
import com.systemdesign.chat.model.User;
import com.systemdesign.chat.repository.*;
import com.systemdesign.chat.service.*;

/**
 * Manual dependency injection and wiring. In production this would be
 * handled by Spring or Guice; here we wire everything explicitly to
 * demonstrate the dependency graph.
 */
public class AppConfig {

    public static final String SERVER_ID = "server-1";
    public static final int HEARTBEAT_INTERVAL_SEC = 30;

    private final UserRepository userRepo;
    private final ConversationRepository convRepo;
    private final MessageRepository msgRepo;
    private final ConnectionRegistry registry;
    private final MessageRouter router;
    private final ChatService chatService;

    public AppConfig() {
        // Repositories
        this.userRepo = new InMemoryUserRepository();
        this.convRepo = new InMemoryConversationRepository();
        this.msgRepo = new InMemoryMessageRepository();

        // Connection infrastructure
        this.registry = new InMemoryConnectionRegistry();
        this.router = new MessageRouter(registry);

        // Services
        PresenceService presenceService = new PresenceService(registry);
        GroupService groupService = new GroupService(convRepo, userRepo);
        MessageService msgService = new MessageService(msgRepo, convRepo, router, userRepo);

        this.chatService = new ChatService(msgService, groupService, presenceService,
                router, convRepo, userRepo);
    }

    public ChatController createController() {
        return new ChatController(chatService);
    }

    /**
     * Seeds the system with 5 demo users.
     */
    public void seedUsers() {
        userRepo.save(new User("alice", "Alice", "https://avatar.example.com/alice.png"));
        userRepo.save(new User("bob", "Bob", "https://avatar.example.com/bob.png"));
        userRepo.save(new User("carol", "Carol", "https://avatar.example.com/carol.png"));
        userRepo.save(new User("dave", "Dave", "https://avatar.example.com/dave.png"));
        userRepo.save(new User("eve", "Eve", "https://avatar.example.com/eve.png"));
        System.out.println("  [SEED] Created 5 demo users: alice, bob, carol, dave, eve");
    }

    /**
     * Sets up simulated WebSocket connections for the given user IDs.
     */
    public void setupConnections(String... userIds) {
        for (String userId : userIds) {
            ConnectionHandler handler = new ConnectionHandler(userId, SERVER_ID, registry);
            handler.connect();
            router.registerConnection(userId, handler);
        }
    }

    /**
     * Disconnects a simulated WebSocket connection for a user.
     */
    public void disconnectUser(String userId) {
        registry.unregister(userId);
    }

    public UserRepository getUserRepo() {
        return userRepo;
    }

    public ConnectionRegistry getRegistry() {
        return registry;
    }

    public MessageRouter getRouter() {
        return router;
    }

    public ChatService getChatService() {
        return chatService;
    }
}
