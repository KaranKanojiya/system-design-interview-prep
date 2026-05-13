package com.systemdesign.messagequeue.repository;

import com.systemdesign.messagequeue.model.Topic;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Topic persistence.
 * Abstracts storage — implementations may use in-memory maps, databases, or ZooKeeper.
 */
public interface TopicRepository {

    /** Saves or updates a topic. */
    void save(Topic topic);

    /** Finds a topic by its unique name. */
    Optional<Topic> findByName(String name);

    /** Returns all registered topics. */
    List<Topic> findAll();

    /** Deletes a topic by name. */
    void deleteByName(String name);

    /** Returns true if a topic with the given name exists. */
    boolean existsByName(String name);
}
