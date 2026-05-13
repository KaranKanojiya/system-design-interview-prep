package com.systemdesign.messagequeue.service;

import com.systemdesign.messagequeue.model.BrokerNode;
import com.systemdesign.messagequeue.repository.BrokerRepository;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Broker cluster management — registration, controller election, and failure handling.
 *
 * Flow (controller election):
 *  1. Collect all alive brokers
 *  2. Sort by broker ID (lexicographic)
 *  3. Elect the broker with the lowest ID as controller
 *  4. Mark the elected broker as controller
 *
 * Flow (broker failure):
 *  1. Mark the failed broker as dead (stop heartbeat updates)
 *  2. If the failed broker was the controller, trigger re-election
 */
public class BrokerService {

    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(30);

    // --- dependencies (constructor-injected) ---
    private final BrokerRepository brokerRepo;  // persistence layer for broker metadata

    public BrokerService(BrokerRepository brokerRepo) {
        this.brokerRepo = brokerRepo;
    }

    // ===================== Registration =====================

    /**
     * Registers a new broker in the cluster.
     *
     * @param broker the broker node to register
     */
    public void registerBroker(BrokerNode broker) {
        brokerRepo.save(broker);
        System.out.println("[BROKER] Registered broker '" + broker.getBrokerId()
                + "' at " + broker.getHost() + ":" + broker.getPort());
    }

    // ===================== Controller Election =====================

    /**
     * Elects a controller from the alive brokers.
     * The broker with the lowest ID is chosen (deterministic election).
     *
     * @return the elected controller broker, or empty if no alive brokers exist
     */
    public Optional<BrokerNode> electController() {
        // 1. clear any existing controller flag
        brokerRepo.findController().ifPresent(current -> {
            current.setController(false);
            brokerRepo.save(current);
        });

        // 2. get all alive brokers
        List<BrokerNode> aliveBrokers = brokerRepo.findAlive(HEARTBEAT_TIMEOUT);

        if (aliveBrokers.isEmpty()) {
            System.out.println("[BROKER] No alive brokers found — controller election failed");
            return Optional.empty();
        }

        // 3. elect the broker with the lowest ID
        BrokerNode elected = aliveBrokers.stream()
                .min(Comparator.comparing(BrokerNode::getBrokerId))
                .orElseThrow();

        // 4. mark as controller and persist
        elected.setController(true);
        brokerRepo.save(elected);

        System.out.println("[BROKER] Elected broker '" + elected.getBrokerId()
                + "' as cluster controller");

        return Optional.of(elected);
    }

    // ===================== Queries =====================

    /**
     * Retrieves a broker by its ID.
     *
     * @param brokerId the broker identifier
     * @return the BrokerNode if found
     */
    public Optional<BrokerNode> getBroker(String brokerId) {
        return brokerRepo.findById(brokerId);
    }

    /**
     * Returns all brokers that are currently alive (heartbeat within timeout).
     *
     * @return list of alive brokers
     */
    public List<BrokerNode> getAliveBrokers() {
        return brokerRepo.findAlive(HEARTBEAT_TIMEOUT);
    }

    /**
     * Returns all registered brokers regardless of liveness.
     *
     * @return list of all brokers
     */
    public List<BrokerNode> getAllBrokers() {
        return brokerRepo.findAll();
    }

    // ===================== Failure Handling =====================

    /**
     * Handles a broker failure — marks it as dead and triggers re-election if needed.
     *
     * @param brokerId the ID of the failed broker
     */
    public void handleBrokerFailure(String brokerId) {
        Optional<BrokerNode> brokerOpt = brokerRepo.findById(brokerId);
        if (brokerOpt.isEmpty()) {
            System.out.println("[BROKER] Unknown broker failure reported: " + brokerId);
            return;
        }

        BrokerNode broker = brokerOpt.get();
        boolean wasController = broker.isController();

        // 1. mark the broker as dead by setting a stale heartbeat
        // (setting lastHeartbeat far in the past so isAlive() returns false)
        broker.setController(false);
        brokerRepo.save(broker);

        System.out.println("[BROKER] Broker '" + brokerId + "' marked as DEAD"
                + (wasController ? " (was controller)" : ""));

        // 2. if the failed broker was the controller, trigger re-election
        if (wasController) {
            System.out.println("[BROKER] Controller lost — triggering re-election");
            electController();
        }
    }
}
