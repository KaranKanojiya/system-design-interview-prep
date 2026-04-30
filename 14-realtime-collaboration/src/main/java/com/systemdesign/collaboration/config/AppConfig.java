package com.systemdesign.collaboration.config;

import com.systemdesign.collaboration.controller.CollaborationController;
import com.systemdesign.collaboration.display.CollaborationStatsDisplay;
import com.systemdesign.collaboration.ot.CRDTResolver;
import com.systemdesign.collaboration.ot.ConflictResolver;
import com.systemdesign.collaboration.ot.OTResolver;
import com.systemdesign.collaboration.repository.*;
import com.systemdesign.collaboration.service.*;
import com.systemdesign.collaboration.strategy.persistence.EventSourcedPersistence;
import com.systemdesign.collaboration.strategy.persistence.PersistenceStrategy;
import com.systemdesign.collaboration.strategy.persistence.SnapshotPersistence;
import com.systemdesign.collaboration.strategy.sync.CRDTSyncStrategy;
import com.systemdesign.collaboration.strategy.sync.OTSyncStrategy;
import com.systemdesign.collaboration.strategy.sync.SyncStrategy;

/**
 * FACTORY — the ONLY place where "new ConcreteClass()" appears.
 *
 * This is the composition root / DI container.  Every concrete implementation
 * is instantiated here and wired together.  The rest of the codebase depends
 * only on interfaces and abstract types.
 *
 * Wiring diagram:
 *
 *   Repositories (InMemory)
 *       ↓
 *   Services (Document, Operation, Permission, Presence, Version, Broadcast)
 *       ↓
 *   Strategies (OT/CRDT sync, Snapshot/EventSourced persistence)
 *       ↓
 *   CollaborationService (FACADE, orchestrates everything)
 *       ↓
 *   CollaborationController (simulated REST/WebSocket)
 *       ↓
 *   CollaborationStatsDisplay (monitoring)
 *
 * Interview note: This mirrors how Spring's @Configuration classes work,
 * or how a Guice module binds interfaces to implementations.
 */
public class AppConfig {

    // ── Repositories ──
    private final DocumentRepository documentRepository;
    private final OperationRepository operationRepository;
    private final VersionRepository versionRepository;

    // ── Strategies ──
    private final ConflictResolver otResolver;
    private final ConflictResolver crdtResolver;
    private final SyncStrategy otSyncStrategy;
    private final SyncStrategy crdtSyncStrategy;
    private final PersistenceStrategy snapshotPersistence;
    private final PersistenceStrategy eventSourcedPersistence;

    // ── Services ──
    private final PermissionService permissionService;
    private final DocumentService documentService;
    private final OperationService operationService;
    private final PresenceService presenceService;
    private final VersionService versionService;
    private final BroadcastService broadcastService;
    private final CollaborationService collaborationService;

    // ── Controller ──
    private final CollaborationController controller;

    // ── Display ──
    private final CollaborationStatsDisplay statsDisplay;

    /**
     * Construct the entire object graph.
     * Default: OT sync strategy + event-sourced persistence.
     */
    public AppConfig() {
        // 1. Repositories
        this.documentRepository = new InMemoryDocumentRepository();
        this.operationRepository = new InMemoryOperationRepository();
        this.versionRepository = new InMemoryVersionRepository();

        // 2. Conflict resolvers
        this.otResolver = new OTResolver();
        this.crdtResolver = new CRDTResolver();

        // 3. Sync strategies
        this.otSyncStrategy = new OTSyncStrategy(otResolver);
        this.crdtSyncStrategy = new CRDTSyncStrategy();

        // 4. Persistence strategies
        this.snapshotPersistence = new SnapshotPersistence();
        this.eventSourcedPersistence = new EventSourcedPersistence();

        // 5. Services — note the wiring order (dependencies must be created first)
        this.permissionService = new PermissionService();
        this.documentService = new DocumentService(documentRepository, permissionService);
        this.operationService = new OperationService(operationRepository);
        this.presenceService = new PresenceService();
        this.versionService = new VersionService(versionRepository, documentRepository, operationRepository);
        this.broadcastService = new BroadcastService();

        // 6. Collaboration service (the facade) — defaults to OT + EventSourced
        this.collaborationService = new CollaborationService(
                documentService,
                operationService,
                permissionService,
                presenceService,
                versionService,
                broadcastService,
                otSyncStrategy,         // default: OT
                eventSourcedPersistence // default: event-sourced
        );

        // 7. Controller
        this.controller = new CollaborationController(
                collaborationService,
                documentService,
                permissionService,
                presenceService,
                versionService,
                broadcastService
        );

        // 8. Stats display
        this.statsDisplay = new CollaborationStatsDisplay(
                documentService,
                operationService,
                presenceService,
                broadcastService,
                otSyncStrategy
        );
    }

    // ── Getters — used by the main App ──

    public CollaborationController getController()           { return controller; }
    public CollaborationService getCollaborationService()    { return collaborationService; }
    public DocumentService getDocumentService()              { return documentService; }
    public OperationService getOperationService()            { return operationService; }
    public PermissionService getPermissionService()          { return permissionService; }
    public PresenceService getPresenceService()              { return presenceService; }
    public VersionService getVersionService()                { return versionService; }
    public BroadcastService getBroadcastService()            { return broadcastService; }
    public CollaborationStatsDisplay getStatsDisplay()       { return statsDisplay; }

    // ── Strategy accessors (for comparing OT vs CRDT in demos) ──

    public ConflictResolver getOtResolver()                  { return otResolver; }
    public ConflictResolver getCrdtResolver()                { return crdtResolver; }
    public SyncStrategy getOtSyncStrategy()                  { return otSyncStrategy; }
    public SyncStrategy getCrdtSyncStrategy()                { return crdtSyncStrategy; }
    public PersistenceStrategy getSnapshotPersistence()      { return snapshotPersistence; }
    public PersistenceStrategy getEventSourcedPersistence()  { return eventSourcedPersistence; }

    /**
     * Create a CRDT-based CollaborationService for comparison demos.
     * Uses the same services but swaps the sync strategy.
     */
    public CollaborationService createCrdtCollaborationService() {
        return new CollaborationService(
                documentService,
                operationService,
                permissionService,
                presenceService,
                versionService,
                broadcastService,
                crdtSyncStrategy,
                eventSourcedPersistence
        );
    }
}
