package com.systemdesign.messagequeue.repository;

import com.systemdesign.messagequeue.model.Offset;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Offset persistence.
 * Stores committed consumer offsets — the position each consumer group has reached
 * in each topic-partition.
 */
public interface OffsetRepository {

    /** Saves or updates a committed offset. */
    void save(Offset offset);

    /** Finds the offset for a specific group, topic, and partition. */
    Optional<Offset> findByGroupTopicPartition(String groupId, String topic, int partition);

    /** Returns all offsets committed by a specific consumer group. */
    List<Offset> findByGroup(String groupId);

    /** Returns all stored offsets. */
    List<Offset> findAll();
}
