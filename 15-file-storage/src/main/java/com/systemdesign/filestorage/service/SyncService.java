package com.systemdesign.filestorage.service;

import com.systemdesign.filestorage.model.ConflictResolution;
import com.systemdesign.filestorage.model.FileMetadata;
import com.systemdesign.filestorage.model.SyncEvent;
import com.systemdesign.filestorage.model.SyncEventType;
import com.systemdesign.filestorage.strategy.sync.ConflictStrategy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SyncService — manages change tracking and conflict resolution for device sync.
 *
 * How sync works (simplified Dropbox model):
 *   1. Each file change (create/modify/delete/move/rename) produces a SyncEvent.
 *   2. Each device has a "cursor" (the timestamp of the last event it saw).
 *   3. To sync: device sends its cursor → server returns all events after that cursor.
 *   4. If two devices modified the same file offline → conflict → ConflictStrategy decides.
 *
 * Push simulation:
 *   In a real system, the server would push notifications to connected devices via
 *   WebSocket/long-polling. We simulate this by logging "push" messages.
 *
 * Call chain:
 *   UploadService → this.recordChange(...) after successful upload
 *   Controller.handleSync → FileStorageService.syncChanges → this.getChangesSince(userId, cursor)
 *   SyncService.resolveConflict → conflictStrategy.resolve(local, remote)
 */
public class SyncService {

    /** All sync events, keyed by userId for efficient lookup. */
    private final Map<String, List<SyncEvent>> eventsByUser = new ConcurrentHashMap<>();

    /** The conflict resolution strategy (injected — can be swapped). */
    private ConflictStrategy conflictStrategy;

    public SyncService(ConflictStrategy conflictStrategy) {
        this.conflictStrategy = conflictStrategy;
    }

    /**
     * Record a file change event.
     * Called by UploadService, TrashService, MetadataService after mutations.
     */
    public SyncEvent recordChange(String userId, String fileId, String fileName,
                                  SyncEventType eventType, String deviceId) {
        SyncEvent event = new SyncEvent(
                UUID.randomUUID().toString(),
                userId,
                fileId,
                fileName,
                eventType,
                Instant.now(),
                deviceId
        );

        eventsByUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(event);

        // Simulate push notification to other devices
        System.out.printf("   [SYNC PUSH] → Notifying other devices of user '%s': %s %s%n",
                userId, eventType, fileName);

        return event;
    }

    /**
     * Get all changes since the given cursor (timestamp).
     * Returns events that happened AFTER the cursor time.
     *
     * @param userId     the user to get changes for
     * @param lastCursor the timestamp of the last event the client saw (null = get all)
     * @return list of new events + the new cursor (timestamp of the latest event)
     */
    public SyncResult getChangesSince(String userId, Instant lastCursor) {
        List<SyncEvent> userEvents = eventsByUser.getOrDefault(userId, new ArrayList<>());

        List<SyncEvent> newEvents;
        if (lastCursor == null) {
            // No cursor — return all events
            newEvents = new ArrayList<>(userEvents);
        } else {
            // Filter events after the cursor
            newEvents = userEvents.stream()
                    .filter(e -> e.getTimestamp().isAfter(lastCursor))
                    .collect(Collectors.toList());
        }

        // New cursor = timestamp of the latest event (or the old cursor if no new events)
        Instant newCursor = newEvents.isEmpty() ? lastCursor :
                newEvents.get(newEvents.size() - 1).getTimestamp();

        return new SyncResult(newEvents, newCursor);
    }

    /**
     * Resolve a conflict between local and remote versions of a file.
     *
     * @return the resolution decision and a description of what happened
     */
    public String resolveConflict(FileMetadata localFile, FileMetadata remoteFile, String username) {
        ConflictResolution resolution = conflictStrategy.resolve(localFile, remoteFile);

        String description;
        switch (resolution) {
            case KEEP_LOCAL:
                description = String.format("KEEP_LOCAL: '%s' — local version wins (updated %s)",
                        localFile.getFileName(), localFile.getUpdatedAt());
                break;
            case KEEP_REMOTE:
                description = String.format("KEEP_REMOTE: '%s' — remote version wins (updated %s)",
                        remoteFile.getFileName(), remoteFile.getUpdatedAt());
                break;
            case KEEP_BOTH:
                // Generate conflict copy name
                String baseName = remoteFile.getFileName();
                String ext = "";
                int dotIdx = baseName.lastIndexOf('.');
                if (dotIdx > 0) {
                    ext = baseName.substring(dotIdx);
                    baseName = baseName.substring(0, dotIdx);
                }
                String conflictName = String.format("%s (conflict copy - %s - %s)%s",
                        baseName, username, LocalDate.now(), ext);
                description = String.format("KEEP_BOTH: '%s' kept + conflict copy created: '%s'",
                        localFile.getFileName(), conflictName);
                break;
            default:
                description = "Unknown resolution: " + resolution;
        }

        return description;
    }

    /**
     * Get the conflict resolution type (for programmatic use).
     */
    public ConflictResolution getConflictResolution(FileMetadata localFile, FileMetadata remoteFile) {
        return conflictStrategy.resolve(localFile, remoteFile);
    }

    /** Swap the conflict strategy at runtime. */
    public void setConflictStrategy(ConflictStrategy strategy) {
        this.conflictStrategy = strategy;
    }

    /** Get all events for a user (for display/debug). */
    public List<SyncEvent> getAllEvents(String userId) {
        return eventsByUser.getOrDefault(userId, new ArrayList<>());
    }

    // ── SyncResult inner class ───────────────────────────────────────

    /**
     * Result of a sync operation: the new events + a cursor for the next sync.
     */
    public static class SyncResult {
        private final List<SyncEvent> events;
        private final Instant newCursor;

        public SyncResult(List<SyncEvent> events, Instant newCursor) {
            this.events = events;
            this.newCursor = newCursor;
        }

        public List<SyncEvent> getEvents() { return events; }
        public Instant getNewCursor() { return newCursor; }
    }
}
