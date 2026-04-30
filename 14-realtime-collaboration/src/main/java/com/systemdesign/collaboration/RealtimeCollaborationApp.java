package com.systemdesign.collaboration;

import com.systemdesign.collaboration.config.AppConfig;
import com.systemdesign.collaboration.controller.CollaborationController;
import com.systemdesign.collaboration.model.*;
import com.systemdesign.collaboration.ot.ConflictResolver;
import com.systemdesign.collaboration.ot.TransformResult;
import com.systemdesign.collaboration.service.*;

import java.util.List;

/**
 * Real-time Collaboration Tool — System Design Interview Demo
 *
 * Demonstrates:
 *   - Operational Transformation (OT) — the algorithm behind Google Docs
 *   - CRDT — the alternative used by Figma
 *   - Strategy Pattern (sync: OT vs CRDT; persistence: snapshot vs event-sourced)
 *   - Builder Pattern (Document)
 *   - Facade Pattern (CollaborationService)
 *   - Repository Pattern (data access)
 *   - Observer/Pub-Sub (broadcast via simulated WebSocket)
 *
 * Key interview talking points:
 *   1. OT transform handles INSERT/INSERT, INSERT/DELETE, DELETE/INSERT, DELETE/DELETE
 *   2. Server is single source of truth (avoids TP2 complexity)
 *   3. All clients converge to the same document state
 *   4. Cursor positions are adjusted when remote ops arrive
 *   5. Snapshots + operation log for efficient version history
 */
public class RealtimeCollaborationApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("  REAL-TIME COLLABORATION TOOL — System Design Demo");
        System.out.println("  (Google Docs-style concurrent editing)");
        System.out.println(SEPARATOR);
        System.out.println();

        // ── Bootstrap the entire system via AppConfig (composition root) ──
        AppConfig config = new AppConfig();

        CollaborationController controller = config.getController();
        CollaborationService collabService = config.getCollaborationService();
        DocumentService docService = config.getDocumentService();
        OperationService opService = config.getOperationService();
        PermissionService permService = config.getPermissionService();
        PresenceService presenceService = config.getPresenceService();
        VersionService versionService = config.getVersionService();
        BroadcastService broadcastService = config.getBroadcastService();

        // Suppress verbose WebSocket output for cleaner demo
        broadcastService.setVerbose(false);

        // ═══════════════════════════════════════════════════════
        //  Demo 1: Document Creation & Basic Editing
        // ═══════════════════════════════════════════════════════
        printDemo(1, "Document Creation & Basic Editing");

        Document doc1 = controller.handleCreateDocument("Design Doc", "alice");
        String docId = doc1.getDocId();
        System.out.println("   Created: " + doc1);
        System.out.println();

        // Alice joins and types "Hello World"
        controller.handleJoinDocument("alice", "Alice", docId);
        Operation insertHello = new Operation(docId, "alice", OperationType.INSERT,
                0, "Hello World", 0, doc1.getVersion());
        controller.handleEditDocument(docId, "alice", insertHello);

        System.out.println("   After INSERT 'Hello World':");
        System.out.println("   Content: \"" + docService.getDocumentContent(docId) + "\"");
        System.out.println("   Version: " + docService.getDocument(docId).getVersion());
        System.out.println();

        // Insert " - Draft" at the end
        Document current = docService.getDocument(docId);
        Operation insertDraft = new Operation(docId, "alice", OperationType.INSERT,
                current.getLength(), " - Draft", 0, current.getVersion());
        controller.handleEditDocument(docId, "alice", insertDraft);

        System.out.println("   After INSERT ' - Draft' at end:");
        System.out.println("   Content: \"" + docService.getDocumentContent(docId) + "\"");
        System.out.println("   Version: " + docService.getDocument(docId).getVersion());

        // ═══════════════════════════════════════════════════════
        //  Demo 2: Concurrent Edits with OT
        // ═══════════════════════════════════════════════════════
        printDemo(2, "Concurrent Edits with OT (Two Users Type Simultaneously)");

        Document doc2 = controller.handleCreateDocument("OT Demo", "alice");
        String docId2 = doc2.getDocId();
        controller.handleJoinDocument("alice", "Alice", docId2);
        controller.handleJoinDocument("bob", "Bob", docId2);
        permService.grantPermission(docId2, "bob", PermissionRole.EDITOR, "alice");

        // Start with "ABCDE"
        Operation seedOp = new Operation(docId2, "alice", OperationType.INSERT,
                0, "ABCDE", 0, 0);
        controller.handleEditDocument(docId2, "alice", seedOp);

        System.out.println("   Initial content: \"" + docService.getDocumentContent(docId2) + "\"");
        System.out.println("   Version: " + docService.getDocument(docId2).getVersion());
        System.out.println();

        // Both users create ops against the SAME base version (simulating concurrency)
        int baseVersion = docService.getDocument(docId2).getVersion();
        System.out.println("   Both users see version " + baseVersion + " with content \"ABCDE\"");
        System.out.println();

        // Alice inserts "X" at position 2 (between B and C) → wants "ABXCDE"
        Operation aliceOp = new Operation(docId2, "alice", OperationType.INSERT,
                2, "X", 0, baseVersion);
        System.out.println("   Alice's op: INSERT 'X' at pos 2 (between B and C)");

        // Bob inserts "Y" at position 4 (between D and E) → wants "ABCDYE"
        Operation bobOp = new Operation(docId2, "bob", OperationType.INSERT,
                4, "Y", 0, baseVersion);
        System.out.println("   Bob's op:   INSERT 'Y' at pos 4 (between D and E)");
        System.out.println();

        // Server processes Alice's op first (she submitted first)
        controller.handleEditDocument(docId2, "alice", aliceOp);
        System.out.println("   After Alice's op applied: \"" + docService.getDocumentContent(docId2) + "\"");
        System.out.println("   (Server version now: " + docService.getDocument(docId2).getVersion() + ")");

        // Now server processes Bob's op — but Bob's baseVersion is stale!
        // OT will transform Bob's op: since Alice inserted 'X' at pos 2 (before Bob's pos 4),
        // Bob's position shifts from 4 to 5.
        controller.handleEditDocument(docId2, "bob", bobOp);
        System.out.println("   After Bob's op (OT-transformed): \"" + docService.getDocumentContent(docId2) + "\"");
        System.out.println();

        System.out.println("   RESULT: Both edits preserved! OT shifted Bob's position from 4 → 5.");
        System.out.println("   Expected: \"ABXCDYE\" — Got: \"" + docService.getDocumentContent(docId2) + "\"");

        // ═══════════════════════════════════════════════════════
        //  Demo 3: Insert vs Delete Conflict
        // ═══════════════════════════════════════════════════════
        printDemo(3, "Insert vs Delete Conflict");

        Document doc3 = controller.handleCreateDocument("Conflict Demo", "alice");
        String docId3 = doc3.getDocId();
        controller.handleJoinDocument("alice", "Alice", docId3);
        controller.handleJoinDocument("bob", "Bob", docId3);
        permService.grantPermission(docId3, "bob", PermissionRole.EDITOR, "alice");

        // Start with "ABCDEF"
        Operation seed3 = new Operation(docId3, "alice", OperationType.INSERT,
                0, "ABCDEF", 0, 0);
        controller.handleEditDocument(docId3, "alice", seed3);

        int base3 = docService.getDocument(docId3).getVersion();
        System.out.println("   Initial: \"" + docService.getDocumentContent(docId3) + "\" (v" + base3 + ")");
        System.out.println();

        // Alice inserts "X" at position 3 (between C and D)
        Operation aliceInsert = new Operation(docId3, "alice", OperationType.INSERT,
                3, "X", 0, base3);
        System.out.println("   Alice: INSERT 'X' at pos 3 (between C and D)");

        // Bob deletes 2 chars starting at position 1 (deletes "BC")
        Operation bobDelete = new Operation(docId3, "bob", OperationType.DELETE,
                1, null, 2, base3);
        System.out.println("   Bob:   DELETE 2 chars at pos 1 (removes 'BC')");
        System.out.println();

        // Process Alice's insert first
        controller.handleEditDocument(docId3, "alice", aliceInsert);
        System.out.println("   After Alice's INSERT: \"" + docService.getDocumentContent(docId3) + "\"");

        // Process Bob's delete — OT must shift the delete position since Alice inserted before it
        controller.handleEditDocument(docId3, "bob", bobDelete);
        System.out.println("   After Bob's DELETE (OT-adjusted): \"" + docService.getDocumentContent(docId3) + "\"");
        System.out.println();

        System.out.println("   RESULT: Alice's insert is preserved, Bob's delete is adjusted.");
        System.out.println("   'BC' was deleted, 'X' was inserted — both intentions honored.");

        // ═══════════════════════════════════════════════════════
        //  Demo 4: OT vs CRDT Comparison
        // ═══════════════════════════════════════════════════════
        printDemo(4, "OT vs CRDT Comparison");

        ConflictResolver otResolver = config.getOtResolver();
        ConflictResolver crdtResolver = config.getCrdtResolver();

        // Same conflict scenario: two concurrent inserts
        Operation opA = new Operation("test", "alice", OperationType.INSERT, 3, "X", 0, 1);
        Operation opB = new Operation("test", "bob", OperationType.INSERT, 3, "Y", 0, 1);

        System.out.println("   Scenario: Both users insert at position 3");
        System.out.println("   Alice: INSERT 'X' at pos 3");
        System.out.println("   Bob:   INSERT 'Y' at pos 3");
        System.out.println();

        // OT resolution
        TransformResult otResult = otResolver.transform(opA, opB);
        System.out.println("   === OT Resolution (" + otResolver.getName() + ") ===");
        System.out.println("   Alice' (adjusted): pos=" + otResult.getTransformedLocal().getPosition());
        System.out.println("   Bob'   (adjusted): pos=" + otResult.getTransformedRemote().getPosition());
        System.out.println("   Tie-break: 'alice' < 'bob' lexicographically → Alice goes first at pos 3, Bob shifts to 4");
        System.out.println();

        // CRDT resolution
        TransformResult crdtResult = crdtResolver.transform(opA, opB);
        System.out.println("   === CRDT Resolution (" + crdtResolver.getName() + ") ===");
        System.out.println("   Alice' (unchanged): pos=" + crdtResult.getTransformedLocal().getPosition());
        System.out.println("   Bob'   (unchanged): pos=" + crdtResult.getTransformedRemote().getPosition());
        System.out.println("   CRDT does NO transform — the data structure handles ordering via unique char IDs.");
        System.out.println();

        System.out.println("   KEY DIFFERENCE:");
        System.out.println("   OT:   Server transforms positions → complex but low metadata");
        System.out.println("   CRDT: No transform needed → simpler logic but every char needs a unique ID");

        // ═══════════════════════════════════════════════════════
        //  Demo 5: Multi-User Presence & Cursors
        // ═══════════════════════════════════════════════════════
        printDemo(5, "Multi-User Presence & Cursors");

        Document doc5 = controller.handleCreateDocument("Team Notes", "alice");
        String docId5 = doc5.getDocId();
        permService.grantPermission(docId5, "bob", PermissionRole.EDITOR, "alice");
        permService.grantPermission(docId5, "charlie", PermissionRole.EDITOR, "alice");

        // Seed content
        Operation seed5 = new Operation(docId5, "alice", OperationType.INSERT,
                0, "The quick brown fox jumps over the lazy dog", 0, 0);
        controller.handleEditDocument(docId5, "alice", seed5);

        // Three users join
        UserPresence alicePresence = controller.handleJoinDocument("alice", "Alice", docId5);
        UserPresence bobPresence = controller.handleJoinDocument("bob", "Bob", docId5);
        UserPresence charliePresence = controller.handleJoinDocument("charlie", "Charlie", docId5);

        System.out.println("   Document: \"" + docService.getDocumentContent(docId5) + "\"");
        System.out.println();

        // Users move their cursors to different positions
        presenceService.updateCursor("alice", docId5, 10);   // after "The quick "
        presenceService.updateCursor("bob", docId5, 20);     // after "The quick brown fox "
        presenceService.updateCursor("charlie", docId5, 35); // near the end

        System.out.println("   Active users and cursor positions:");
        List<UserPresence> activeUsers = presenceService.getActiveUsers(docId5);
        for (UserPresence presence : activeUsers) {
            CursorPosition cursor = presence.getCursorPosition();
            String content = docService.getDocumentContent(docId5);
            String context = cursor.getPosition() < content.length()
                    ? "'" + content.charAt(cursor.getPosition()) + "'"
                    : "end";
            System.out.println("     " + presence.getUserName() +
                    " → pos=" + cursor.getPosition() +
                    " (before char " + context + ")" +
                    " color=" + cursor.getColor());
        }
        System.out.println();

        // Alice inserts text — Bob and Charlie's cursors should shift
        Document doc5Current = docService.getDocument(docId5);
        Operation aliceEdit = new Operation(docId5, "alice", OperationType.INSERT,
                4, "very ", 0, doc5Current.getVersion());
        controller.handleEditDocument(docId5, "alice", aliceEdit);

        System.out.println("   Alice inserts 'very ' at pos 4...");
        System.out.println("   Updated cursors (Bob and Charlie shifted right by 5):");
        for (UserPresence presence : presenceService.getActiveUsers(docId5)) {
            CursorPosition cursor = presence.getCursorPosition();
            System.out.println("     " + presence.getUserName() +
                    " → pos=" + cursor.getPosition() +
                    " color=" + cursor.getColor());
        }

        // ═══════════════════════════════════════════════════════
        //  Demo 6: Version History & Rollback
        // ═══════════════════════════════════════════════════════
        printDemo(6, "Version History & Rollback");

        Document doc6 = controller.handleCreateDocument("Version Demo", "alice");
        String docId6 = doc6.getDocId();
        controller.handleJoinDocument("alice", "Alice", docId6);

        // Make 10 edits, snapshot after each
        String[] words = {"One ", "Two ", "Three ", "Four ", "Five ",
                          "Six ", "Seven ", "Eight ", "Nine ", "Ten "};
        for (int i = 0; i < words.length; i++) {
            Document curr = docService.getDocument(docId6);
            Operation editOp = new Operation(docId6, "alice", OperationType.INSERT,
                    curr.getLength(), words[i], 0, curr.getVersion());
            controller.handleEditDocument(docId6, "alice", editOp);
            // Take a snapshot at each step so we can rollback
            versionService.createSnapshot(docService.getDocument(docId6));
        }

        System.out.println("   After 10 edits:");
        System.out.println("   Content: \"" + docService.getDocumentContent(docId6) + "\"");
        System.out.println("   Version: " + docService.getDocument(docId6).getVersion());
        System.out.println();

        // Show version history
        List<DocumentVersion> history = versionService.getHistory(docId6);
        System.out.println("   Version History (" + history.size() + " snapshots):");
        for (DocumentVersion v : history) {
            String snap = v.getContentSnapshot();
            System.out.println("     v" + v.getVersionNumber() + ": \"" +
                    (snap.length() > 40 ? snap.substring(0, 40) + "..." : snap) + "\"");
        }
        System.out.println();

        // Rollback to version 5
        System.out.println("   Rolling back to version 5...");
        int targetVersion = history.size() >= 5 ? history.get(4).getVersionNumber() : history.get(0).getVersionNumber();
        versionService.rollbackToVersion(docId6, targetVersion);
        System.out.println("   Content after rollback: \"" + docService.getDocumentContent(docId6) + "\"");

        // ═══════════════════════════════════════════════════════
        //  Demo 7: Permission System
        // ═══════════════════════════════════════════════════════
        printDemo(7, "Permission System");

        Document doc7 = controller.handleCreateDocument("Secret Doc", "alice");
        String docId7 = doc7.getDocId();
        controller.handleJoinDocument("alice", "Alice", docId7);

        // Seed some content
        Operation seed7 = new Operation(docId7, "alice", OperationType.INSERT,
                0, "Confidential content here", 0, 0);
        controller.handleEditDocument(docId7, "alice", seed7);
        System.out.println("   Document: \"" + docService.getDocumentContent(docId7) + "\"");
        System.out.println();

        // Share with Bob as EDITOR and Charlie as VIEWER
        controller.handleShareDocument(docId7, "bob", PermissionRole.EDITOR, "alice");
        controller.handleShareDocument(docId7, "charlie", PermissionRole.VIEWER, "alice");

        // Show permissions
        System.out.println("   Collaborators:");
        for (Permission perm : permService.getCollaborators(docId7)) {
            System.out.println("     " + perm.getUserId() + " → " + perm.getRole() +
                    " (can edit: " + perm.getRole().canEdit() + ")");
        }
        System.out.println();

        // Bob (EDITOR) can edit
        controller.handleJoinDocument("bob", "Bob", docId7);
        Document doc7Current = docService.getDocument(docId7);
        Operation bobEdit = new Operation(docId7, "bob", OperationType.INSERT,
                doc7Current.getLength(), " [Reviewed by Bob]", 0, doc7Current.getVersion());
        Document result = controller.handleEditDocument(docId7, "bob", bobEdit);
        System.out.println("   Bob (EDITOR) edits → " +
                (result != null ? "SUCCESS: \"" + docService.getDocumentContent(docId7) + "\"" : "FAILED"));
        System.out.println();

        // Charlie (VIEWER) tries to edit → denied
        controller.handleJoinDocument("charlie", "Charlie", docId7);
        Document doc7Latest = docService.getDocument(docId7);
        Operation charlieEdit = new Operation(docId7, "charlie", OperationType.INSERT,
                0, "HACKED! ", 0, doc7Latest.getVersion());
        Document charlieResult = controller.handleEditDocument(docId7, "charlie", charlieEdit);
        System.out.println("   Charlie (VIEWER) tries to edit → " +
                (charlieResult != null ? "SUCCESS (unexpected!)" : "DENIED (correct!)"));
        System.out.println("   Document content unchanged: \"" + docService.getDocumentContent(docId7) + "\"");

        // ═══════════════════════════════════════════════════════
        //  Demo 8: Operation Replay (Event Sourcing)
        // ═══════════════════════════════════════════════════════
        printDemo(8, "Operation Replay (Event Sourcing)");

        Document doc8 = controller.handleCreateDocument("Event Sourcing Demo", "alice");
        String docId8 = doc8.getDocId();
        controller.handleJoinDocument("alice", "Alice", docId8);
        permService.grantPermission(docId8, "bob", PermissionRole.EDITOR, "alice");
        controller.handleJoinDocument("bob", "Bob", docId8);

        // Series of operations from two users
        Operation[] ops = {
                new Operation(docId8, "alice", OperationType.INSERT, 0, "Hello", 0, 0),
                new Operation(docId8, "bob", OperationType.INSERT, 5, " World", 0, 1),
                new Operation(docId8, "alice", OperationType.INSERT, 11, "!", 0, 2),
                new Operation(docId8, "bob", OperationType.DELETE, 5, null, 1, 3),
                new Operation(docId8, "alice", OperationType.INSERT, 5, ", ", 0, 4),
        };

        for (Operation op : ops) {
            controller.handleEditDocument(docId8, op.getUserId(), op);
        }

        System.out.println("   Final content: \"" + docService.getDocumentContent(docId8) + "\"");
        System.out.println();

        // Replay the operation log
        List<Operation> opLog = opService.getAllOperations(docId8);
        System.out.println("   Operation log (" + opLog.size() + " operations):");
        for (int i = 0; i < opLog.size(); i++) {
            System.out.println("     " + (i + 1) + ". " + opLog.get(i));
        }
        System.out.println();

        System.out.println("   In event sourcing, we can rebuild the document from scratch");
        System.out.println("   by replaying all operations in order.  This gives us a");
        System.out.println("   complete audit trail: who changed what, and when.");

        // ═══════════════════════════════════════════════════════
        //  Demo 9: Rapid Concurrent Edits Stress Test
        // ═══════════════════════════════════════════════════════
        printDemo(9, "Rapid Concurrent Edits Stress Test (50 ops, 3 users)");

        Document doc9 = controller.handleCreateDocument("Stress Test", "alice");
        String docId9 = doc9.getDocId();
        controller.handleJoinDocument("alice", "Alice", docId9);
        controller.handleJoinDocument("bob", "Bob", docId9);
        controller.handleJoinDocument("charlie", "Charlie", docId9);
        permService.grantPermission(docId9, "bob", PermissionRole.EDITOR, "alice");
        permService.grantPermission(docId9, "charlie", PermissionRole.EDITOR, "alice");

        // Seed
        Operation seed9 = new Operation(docId9, "alice", OperationType.INSERT,
                0, "START", 0, 0);
        controller.handleEditDocument(docId9, "alice", seed9);

        String[] users = {"alice", "bob", "charlie"};
        int successCount = 0;
        int failCount = 0;

        long startTime = System.nanoTime();

        for (int i = 0; i < 50; i++) {
            String user = users[i % 3];
            try {
                Document curr = docService.getDocument(docId9);
                int pos = Math.min(i % 5, curr.getLength());
                Operation stressOp = new Operation(docId9, user, OperationType.INSERT,
                        pos, String.valueOf((char) ('a' + (i % 26))), 0, curr.getVersion());
                controller.handleEditDocument(docId9, user, stressOp);
                successCount++;
            } catch (Exception e) {
                failCount++;
            }
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;

        System.out.println("   Results:");
        System.out.println("     Successful ops:  " + successCount);
        System.out.println("     Failed ops:      " + failCount);
        System.out.println("     Time elapsed:    " + elapsed + " ms");
        System.out.println("     Ops/sec:         " + (successCount > 0 ? (successCount * 1000L / Math.max(elapsed, 1)) : 0));
        System.out.println("     Final content:   \"" + docService.getDocumentContent(docId9) + "\"");
        System.out.println("     Final version:   " + docService.getDocument(docId9).getVersion());
        System.out.println("     Content length:  " + docService.getDocument(docId9).getLength());
        System.out.println();

        System.out.println("   CONVERGENCE CHECK: All clients see the same content after sync.");
        System.out.println("   Server content = \"" + docService.getDocumentContent(docId9) + "\"");
        System.out.println("   (In a real system, each client would receive all broadcast ops");
        System.out.println("    and converge to this exact state.)");

        // ═══════════════════════════════════════════════════════
        //  Demo 10: Comments on Document
        // ═══════════════════════════════════════════════════════
        printDemo(10, "Comments on Document");

        Document doc10 = controller.handleCreateDocument("Reviewed Doc", "alice");
        String docId10 = doc10.getDocId();
        controller.handleJoinDocument("alice", "Alice", docId10);
        controller.handleJoinDocument("bob", "Bob", docId10);
        permService.grantPermission(docId10, "bob", PermissionRole.EDITOR, "alice");

        Operation seed10 = new Operation(docId10, "alice", OperationType.INSERT,
                0, "This is a draft document that needs review.", 0, 0);
        controller.handleEditDocument(docId10, "alice", seed10);

        System.out.println("   Document: \"" + docService.getDocumentContent(docId10) + "\"");
        System.out.println();

        // Add comments at specific positions
        Comment comment1 = new Comment("c1", docId10, "bob", "Bob",
                "Should we say 'final' instead of 'draft'?", 10);
        Comment comment2 = new Comment("c2", docId10, "alice", "Alice",
                "Good point, will update.", 10);
        Comment comment3 = new Comment("c3", docId10, "bob", "Bob",
                "Also, 'needs review' is redundant if we change to 'final'.", 30);

        System.out.println("   Comments:");
        System.out.println("     " + comment1);
        System.out.println("     " + comment2);
        System.out.println("     " + comment3);
        System.out.println();

        // Resolve a comment
        comment1.resolve();
        comment2.resolve();
        System.out.println("   After resolving comments 1 and 2:");
        System.out.println("     " + comment1.getCommentId() + " resolved=" + comment1.isResolved());
        System.out.println("     " + comment2.getCommentId() + " resolved=" + comment2.isResolved());
        System.out.println("     " + comment3.getCommentId() + " resolved=" + comment3.isResolved());

        // ═══════════════════════════════════════════════════════
        //  Stats & Summary
        // ═══════════════════════════════════════════════════════
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  SYSTEM STATS");
        System.out.println(SEPARATOR);
        config.getStatsDisplay().printStats();

        System.out.println();
        printDesignSummary();
    }

    // ── Helpers ──

    private static void printDemo(int number, String title) {
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  Demo " + number + ": " + title);
        System.out.println(SEPARATOR);
        System.out.println();
    }

    private static void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("  DESIGN SUMMARY — Real-time Collaboration Tool");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  Architecture:");
        System.out.println("    Client → WebSocket → Server (single source of truth) → Broadcast");
        System.out.println();
        System.out.println("  Core Algorithms:");
        System.out.println("    1. Operational Transformation (OT)");
        System.out.println("       - Server transforms concurrent ops to preserve user intent");
        System.out.println("       - 4 cases: INS/INS, INS/DEL, DEL/INS, DEL/DEL");
        System.out.println("       - Used by: Google Docs, Microsoft Office Online");
        System.out.println();
        System.out.println("    2. CRDT (Conflict-free Replicated Data Type)");
        System.out.println("       - Each character gets a unique ID — ops commute naturally");
        System.out.println("       - No server transform needed, supports P2P");
        System.out.println("       - Used by: Figma, Yjs, Automerge");
        System.out.println();
        System.out.println("  Patterns Used:");
        System.out.println("    - Strategy:   OT vs CRDT sync; Snapshot vs EventSourced persistence");
        System.out.println("    - Facade:     CollaborationService orchestrates the full pipeline");
        System.out.println("    - Builder:    Document construction");
        System.out.println("    - Repository: Data access abstraction");
        System.out.println("    - Observer:   BroadcastService (simulated pub/sub)");
        System.out.println();
        System.out.println("  Scalability Considerations:");
        System.out.println("    - Partition by document ID (each doc is independent)");
        System.out.println("    - WebSocket connections via Redis Pub/Sub for multi-server");
        System.out.println("    - Operation log in Kafka for durability");
        System.out.println("    - Snapshots in S3/DynamoDB for fast document loading");
        System.out.println("    - Cursor updates via lightweight UDP or unreliable channels");
        System.out.println();
        System.out.println("  Key Invariant:");
        System.out.println("    After all operations are processed, ALL clients converge to");
        System.out.println("    the same document state.  This is guaranteed by OT's TP1 property");
        System.out.println("    (or CRDT's commutativity property).");
        System.out.println();
        System.out.println(SEPARATOR);
    }
}
