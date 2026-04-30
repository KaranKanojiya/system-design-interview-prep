package com.systemdesign.filestorage.model;

import java.time.Instant;

/**
 * SyncEvent — a single change event in the sync log.
 *
 * Design decisions:
 * - deviceId: identifies which device made the change. Other devices with the same
 *   userId need to apply this change. The originating device can skip it.
 * - timestamp: used as a logical clock for ordering and as a cursor for sync pagination.
 *
 * Real-world parallel:
 * - Dropbox uses a cursor-based changelog: client sends last cursor, server returns
 *   all events since that cursor. Our SyncService mimics this approach.
 *
 * Call chain:
 *   SyncService.recordChange(userId, fileId, eventType, deviceId) → creates SyncEvent
 *   SyncService.getChangesSince(userId, lastCursor) → returns List<SyncEvent>
 */
public class SyncEvent {

    private final String eventId;
    private final String userId;
    private final String fileId;
    private final String fileName;
    private final SyncEventType eventType;
    private final Instant timestamp;
    private final String deviceId;

    public SyncEvent(String eventId, String userId, String fileId, String fileName,
                     SyncEventType eventType, Instant timestamp, String deviceId) {
        this.eventId = eventId;
        this.userId = userId;
        this.fileId = fileId;
        this.fileName = fileName;
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.deviceId = deviceId;
    }

    public String getEventId() { return eventId; }
    public String getUserId() { return userId; }
    public String getFileId() { return fileId; }
    public String getFileName() { return fileName; }
    public SyncEventType getEventType() { return eventType; }
    public Instant getTimestamp() { return timestamp; }
    public String getDeviceId() { return deviceId; }

    @Override
    public String toString() {
        return String.format("SyncEvent{id='%s', user='%s', file='%s', type=%s, device='%s', time=%s}",
                eventId, userId, fileName, eventType, deviceId, timestamp);
    }
}
