package com.systemdesign.collaboration.display;

import com.systemdesign.collaboration.model.Document;
import com.systemdesign.collaboration.model.UserPresence;
import com.systemdesign.collaboration.service.*;
import com.systemdesign.collaboration.strategy.sync.OTSyncStrategy;
import com.systemdesign.collaboration.strategy.sync.SyncStrategy;

import java.util.List;

/**
 * Displays statistics about the collaboration system.
 *
 * Shows:
 *   - Active documents count
 *   - Total operations processed
 *   - Connected users
 *   - Average operations per second (simulated)
 *   - Conflict resolution stats (OT only)
 */
public class CollaborationStatsDisplay {

    private final DocumentService documentService;
    private final OperationService operationService;
    private final PresenceService presenceService;
    private final BroadcastService broadcastService;
    private final SyncStrategy syncStrategy;

    public CollaborationStatsDisplay(DocumentService documentService,
                                     OperationService operationService,
                                     PresenceService presenceService,
                                     BroadcastService broadcastService,
                                     SyncStrategy syncStrategy) {
        this.documentService = documentService;
        this.operationService = operationService;
        this.presenceService = presenceService;
        this.broadcastService = broadcastService;
        this.syncStrategy = syncStrategy;
    }

    /**
     * Print a full stats report to the console.
     */
    public void printStats() {
        System.out.println("   ┌─────────────────────────────────────────────────┐");
        System.out.println("   │          COLLABORATION SYSTEM STATS             │");
        System.out.println("   ├─────────────────────────────────────────────────┤");

        // Active documents
        List<Document> docs = documentService.getAllDocuments();
        System.out.printf("   │ Active Documents:     %-25d│%n", docs.size());

        // Total operations
        int totalOps = 0;
        for (Document doc : docs) {
            totalOps += operationService.getOperationCount(doc.getDocId());
        }
        System.out.printf("   │ Total Operations:     %-25d│%n", totalOps);

        // Connected users per document
        for (Document doc : docs) {
            List<String> users = broadcastService.getConnectedUsers(doc.getDocId());
            List<UserPresence> active = presenceService.getActiveUsers(doc.getDocId());
            System.out.printf("   │ Doc '%s': %d connected, %d active    │%n",
                    truncate(doc.getTitle(), 12), users.size(), active.size());
        }

        // Sync strategy info
        System.out.printf("   │ Sync Strategy:        %-25s│%n",
                truncate(syncStrategy.getName(), 25));

        // OT-specific stats
        if (syncStrategy instanceof OTSyncStrategy otSync) {
            System.out.printf("   │ Conflicts Resolved:   %-25d│%n",
                    otSync.getConflictsResolved());
        }

        System.out.println("   └─────────────────────────────────────────────────┘");
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}
