package com.systemdesign.messagequeue.repository;

import com.systemdesign.messagequeue.model.ConsumerGroup;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for ConsumerGroup persistence.
 * Abstracts storage of consumer group metadata and membership.
 */
public interface ConsumerGroupRepository {

    /** Saves or updates a consumer group. */
    void save(ConsumerGroup group);

    /** Finds a consumer group by its unique ID. */
    Optional<ConsumerGroup> findById(String groupId);

    /** Returns all registered consumer groups. */
    List<ConsumerGroup> findAll();

    /** Deletes a consumer group by ID. */
    void deleteById(String groupId);
}
