package com.systemdesign.ecommerce.saga;

import com.systemdesign.ecommerce.model.Order;

/**
 * SagaOrchestrator — Interface for the Saga pattern.
 *
 * Interview notes:
 * ──────────────────────────────────────────────────────────────────
 * WHY SAGA INSTEAD OF 2PC (TWO-PHASE COMMIT)?
 *
 * In a microservice architecture, each service owns its own database.
 * Traditional 2PC requires a global transaction coordinator that holds
 * locks across all participants until everyone votes "commit". This has
 * serious problems at scale:
 *   1) Latency — lock duration = slowest participant
 *   2) Availability — if the coordinator crashes mid-commit, all
 *      participants are stuck holding locks (blocking protocol)
 *   3) Coupling — every service must support the XA protocol
 *
 * The Saga pattern replaces one atomic transaction with a sequence of
 * local transactions, each paired with a compensating action:
 *   Step 1: Reserve Inventory    ← compensate: Release Inventory
 *   Step 2: Charge Payment       ← compensate: Refund Payment
 *   Step 3: Create Shipment      ← compensate: Cancel Shipment
 *
 * If step N fails, we run compensations for steps N-1 … 1 in reverse order.
 * This gives us eventual consistency without distributed locks.
 *
 * Trade-off: sagas provide ACD (Atomicity, Consistency, Durability) but
 * NOT Isolation — intermediate states are visible. This is acceptable for
 * e-commerce because the order status enum makes the current state explicit.
 * ──────────────────────────────────────────────────────────────────
 */
public interface SagaOrchestrator {

    /**
     * Executes the saga for the given order.
     * On success: all steps completed, order is ready for fulfillment.
     * On failure: compensating actions are run, SagaResult documents what happened.
     */
    SagaResult execute(Order order);
}
