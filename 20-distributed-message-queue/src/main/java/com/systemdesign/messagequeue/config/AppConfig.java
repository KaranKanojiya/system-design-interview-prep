package com.systemdesign.messagequeue.config;

// Wiring: AppConfig is the composition root / factory for the entire Distributed Message Queue.
// All concrete class instantiation happens here — lazily initialized, with strategy setters
// that clear dependent objects so the graph is rebuilt on the next access.

import com.systemdesign.messagequeue.controller.MessageQueueController;
import com.systemdesign.messagequeue.display.MessageQueueStatsDisplay;
import com.systemdesign.messagequeue.engine.ConsumerGroupCoordinator;
import com.systemdesign.messagequeue.engine.MessageRouter;
import com.systemdesign.messagequeue.engine.PartitionManager;
import com.systemdesign.messagequeue.engine.ReplicationEngine;
import com.systemdesign.messagequeue.repository.InMemoryBrokerRepository;
import com.systemdesign.messagequeue.repository.InMemoryConsumerGroupRepository;
import com.systemdesign.messagequeue.repository.InMemoryOffsetRepository;
import com.systemdesign.messagequeue.repository.InMemoryTopicRepository;
import com.systemdesign.messagequeue.service.BrokerService;
import com.systemdesign.messagequeue.service.ConsumerService;
import com.systemdesign.messagequeue.service.MessageQueueService;
import com.systemdesign.messagequeue.service.MetricsService;
import com.systemdesign.messagequeue.service.ProducerService;
import com.systemdesign.messagequeue.service.RetentionService;
import com.systemdesign.messagequeue.service.TopicService;
import com.systemdesign.messagequeue.strategy.delivery.AtLeastOnceDeliveryStrategy;
import com.systemdesign.messagequeue.strategy.delivery.DeliveryStrategy;
import com.systemdesign.messagequeue.strategy.partitioning.HashPartitioningStrategy;
import com.systemdesign.messagequeue.strategy.partitioning.PartitioningStrategy;
import com.systemdesign.messagequeue.strategy.storage.StorageStrategy;
import com.systemdesign.messagequeue.strategy.storage.TimeBasedRetentionStrategy;

/**
 * AppConfig — FACTORY / Composition Root.
 *
 * The ONLY place where "new ConcreteClass()" appears. All fields are lazily initialized.
 * Strategy setters clear dependent objects so the wiring graph rebuilds on next access.
 *
 * Dependency wiring graph:
 *
 *   Repositories: TopicRepository, ConsumerGroupRepository, BrokerRepository, OffsetRepository
 *       |
 *   Engines: PartitionManager, ConsumerGroupCoordinator, MessageRouter, ReplicationEngine
 *       |
 *   Strategies: PartitioningStrategy, DeliveryStrategy, StorageStrategy
 *       |
 *   Services: TopicService, ProducerService, ConsumerService, BrokerService,
 *             RetentionService, MetricsService -> MessageQueueService (FACADE)
 *       |
 *   Controller: MessageQueueController
 *       |
 *   Display: MessageQueueStatsDisplay
 */
public class AppConfig {

    // ── Repositories ────────────────────────────────────────────────────
    private InMemoryTopicRepository topicRepository;
    private InMemoryConsumerGroupRepository consumerGroupRepository;
    private InMemoryBrokerRepository brokerRepository;
    private InMemoryOffsetRepository offsetRepository;

    // ── Engines ─────────────────────────────────────────────────────────
    private PartitionManager partitionManager;
    private ConsumerGroupCoordinator consumerGroupCoordinator;
    private MessageRouter messageRouter;
    private ReplicationEngine replicationEngine;

    // ── Strategies (swappable via setters) ───────────────────────────────
    private PartitioningStrategy partitioningStrategy;
    private DeliveryStrategy deliveryStrategy;
    private StorageStrategy storageStrategy;

    // ── Services ─────────────────────────────────────────────────────────
    private TopicService topicService;
    private ProducerService producerService;
    private ConsumerService consumerService;
    private BrokerService brokerService;
    private RetentionService retentionService;
    private MetricsService metricsService;
    private MessageQueueService messageQueueService;

    // ── Controller & Display ─────────────────────────────────────────────
    private MessageQueueController messageQueueController;
    private MessageQueueStatsDisplay messageQueueStatsDisplay;

    // ══════════════════════════════════════════════════════════════════════
    //  Repository getters (lazy)
    // ══════════════════════════════════════════════════════════════════════

    public InMemoryTopicRepository getTopicRepository() {
        if (topicRepository == null) {
            topicRepository = new InMemoryTopicRepository();
        }
        return topicRepository;
    }

    public InMemoryConsumerGroupRepository getConsumerGroupRepository() {
        if (consumerGroupRepository == null) {
            consumerGroupRepository = new InMemoryConsumerGroupRepository();
        }
        return consumerGroupRepository;
    }

    public InMemoryBrokerRepository getBrokerRepository() {
        if (brokerRepository == null) {
            brokerRepository = new InMemoryBrokerRepository();
        }
        return brokerRepository;
    }

    public InMemoryOffsetRepository getOffsetRepository() {
        if (offsetRepository == null) {
            offsetRepository = new InMemoryOffsetRepository();
        }
        return offsetRepository;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Engine getters (lazy)
    // ══════════════════════════════════════════════════════════════════════

    public PartitionManager getPartitionManager() {
        if (partitionManager == null) {
            partitionManager = new PartitionManager();
        }
        return partitionManager;
    }

    public ConsumerGroupCoordinator getConsumerGroupCoordinator() {
        if (consumerGroupCoordinator == null) {
            consumerGroupCoordinator = new ConsumerGroupCoordinator();
        }
        return consumerGroupCoordinator;
    }

    public MessageRouter getMessageRouter() {
        if (messageRouter == null) {
            messageRouter = new MessageRouter();
        }
        return messageRouter;
    }

    public ReplicationEngine getReplicationEngine() {
        if (replicationEngine == null) {
            // wiring: default replication factor of 3 for durability
            replicationEngine = new ReplicationEngine(3);
        }
        return replicationEngine;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Strategy getters (lazy, with defaults)
    // ══════════════════════════════════════════════════════════════════════

    public PartitioningStrategy getPartitioningStrategy() {
        if (partitioningStrategy == null) {
            partitioningStrategy = new HashPartitioningStrategy();
        }
        return partitioningStrategy;
    }

    public DeliveryStrategy getDeliveryStrategy() {
        if (deliveryStrategy == null) {
            deliveryStrategy = new AtLeastOnceDeliveryStrategy();
        }
        return deliveryStrategy;
    }

    public StorageStrategy getStorageStrategy() {
        if (storageStrategy == null) {
            storageStrategy = new TimeBasedRetentionStrategy();
        }
        return storageStrategy;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Strategy setters — clear dependents so graph rebuilds lazily
    // ══════════════════════════════════════════════════════════════════════

    /** Swap the partitioning strategy; clears producer service -> MQ service -> controller. */
    public void setPartitioningStrategy(PartitioningStrategy partitioningStrategy) {
        this.partitioningStrategy = partitioningStrategy;
        this.producerService = null;
        this.messageQueueService = null;
        this.messageQueueController = null;
        this.messageQueueStatsDisplay = null;
    }

    /** Swap the delivery strategy; clears consumer service -> MQ service -> controller. */
    public void setDeliveryStrategy(DeliveryStrategy deliveryStrategy) {
        this.deliveryStrategy = deliveryStrategy;
        this.consumerService = null;
        this.messageQueueService = null;
        this.messageQueueController = null;
        this.messageQueueStatsDisplay = null;
    }

    /** Swap the storage strategy; clears retention service -> MQ service -> controller. */
    public void setStorageStrategy(StorageStrategy storageStrategy) {
        this.storageStrategy = storageStrategy;
        this.retentionService = null;
        this.messageQueueService = null;
        this.messageQueueController = null;
        this.messageQueueStatsDisplay = null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Service getters (lazy)
    // ══════════════════════════════════════════════════════════════════════

    public TopicService getTopicService() {
        if (topicService == null) {
            topicService = new TopicService(getTopicRepository(), getPartitionManager());
        }
        return topicService;
    }

    public ProducerService getProducerService() {
        if (producerService == null) {
            producerService = new ProducerService(
                    getPartitionManager(), getPartitioningStrategy(),
                    getReplicationEngine(), getTopicRepository());
        }
        return producerService;
    }

    public ConsumerService getConsumerService() {
        if (consumerService == null) {
            consumerService = new ConsumerService(
                    getPartitionManager(), getConsumerGroupCoordinator(),
                    getDeliveryStrategy());
        }
        return consumerService;
    }

    public BrokerService getBrokerService() {
        if (brokerService == null) {
            brokerService = new BrokerService(getBrokerRepository());
        }
        return brokerService;
    }

    public RetentionService getRetentionService() {
        if (retentionService == null) {
            retentionService = new RetentionService(getPartitionManager(), getStorageStrategy());
        }
        return retentionService;
    }

    public MetricsService getMetricsService() {
        if (metricsService == null) {
            metricsService = new MetricsService();
        }
        return metricsService;
    }

    public MessageQueueService getMessageQueueService() {
        if (messageQueueService == null) {
            messageQueueService = new MessageQueueService(
                    getTopicService(), getProducerService(), getConsumerService(),
                    getBrokerService(), getRetentionService(), getMetricsService());
        }
        return messageQueueService;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Controller & Display getters (lazy)
    // ══════════════════════════════════════════════════════════════════════

    public MessageQueueController getController() {
        if (messageQueueController == null) {
            messageQueueController = new MessageQueueController(
                    getMessageQueueService(), getMetricsService());
        }
        return messageQueueController;
    }

    public MessageQueueStatsDisplay getStatsDisplay() {
        if (messageQueueStatsDisplay == null) {
            messageQueueStatsDisplay = new MessageQueueStatsDisplay(
                    getMessageQueueService(), getTopicService(), getProducerService(),
                    getConsumerService(), getBrokerService(), getMetricsService(),
                    getPartitionManager(), getConsumerGroupCoordinator());
        }
        return messageQueueStatsDisplay;
    }
}
