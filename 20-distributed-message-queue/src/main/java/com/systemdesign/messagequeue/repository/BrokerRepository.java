package com.systemdesign.messagequeue.repository;

import com.systemdesign.messagequeue.model.BrokerNode;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for BrokerNode persistence.
 * Abstracts storage of broker metadata, including controller election and liveness.
 */
public interface BrokerRepository {

    /** Saves or updates a broker node. */
    void save(BrokerNode broker);

    /** Finds a broker by its unique ID. */
    Optional<BrokerNode> findById(String brokerId);

    /** Finds the current controller broker (if any). */
    Optional<BrokerNode> findController();

    /** Returns all registered brokers. */
    List<BrokerNode> findAll();

    /** Returns brokers that have sent a heartbeat within the given timeout. */
    List<BrokerNode> findAlive(Duration timeout);

    /** Deletes a broker by ID. */
    void deleteById(String brokerId);
}
