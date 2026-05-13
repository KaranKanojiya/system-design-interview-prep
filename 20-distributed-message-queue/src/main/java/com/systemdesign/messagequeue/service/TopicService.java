package com.systemdesign.messagequeue.service;

import com.systemdesign.messagequeue.engine.PartitionManager;
import com.systemdesign.messagequeue.model.Topic;
import com.systemdesign.messagequeue.repository.TopicRepository;

import java.util.List;
import java.util.Optional;

/**
 * Topic lifecycle management — create, delete, and query topics.
 *
 * Flow (create topic):
 *  1. Validate name uniqueness and partition count
 *  2. Create Topic model and persist via TopicRepository
 *  3. Create N partitions via PartitionManager (one CommitLog per partition)
 *  4. Return the created Topic
 */
public class TopicService {

    // --- dependencies (constructor-injected) ---
    private final TopicRepository topicRepo;          // persistence layer for topics
    private final PartitionManager partitionManager;  // manages partition commit logs

    public TopicService(TopicRepository topicRepo, PartitionManager partitionManager) {
        this.topicRepo = topicRepo;
        this.partitionManager = partitionManager;
    }

    // ===================== Topic Lifecycle =====================

    /**
     * Creates a new topic with the specified number of partitions and replication factor.
     *
     * @param name              unique topic name
     * @param partitions        number of partitions (must be >= 1)
     * @param replicationFactor number of replicas per partition (must be >= 1)
     * @return the created Topic
     * @throws IllegalArgumentException if name is blank or partitions/replicationFactor < 1
     * @throws IllegalStateException    if a topic with the same name already exists
     */
    public Topic createTopic(String name, int partitions, int replicationFactor) {
        // 1. validate inputs
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Topic name must not be blank");
        }
        if (partitions < 1) {
            throw new IllegalArgumentException("Partition count must be >= 1, got: " + partitions);
        }
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("Replication factor must be >= 1, got: " + replicationFactor);
        }

        // 2. check for duplicate topic name
        if (topicRepo.existsByName(name)) {
            throw new IllegalStateException("Topic already exists: " + name);
        }

        // 3. create the Topic model and persist it
        Topic topic = new Topic(name, partitions, replicationFactor);
        topicRepo.save(topic);

        // 4. create one CommitLog per partition via PartitionManager
        for (int i = 0; i < partitions; i++) {
            partitionManager.createPartition(name, i);
        }

        System.out.println("[TOPIC] Created topic '" + name + "' with " + partitions
                + " partitions, replicationFactor=" + replicationFactor);
        return topic;
    }

    /**
     * Deletes a topic and all its partitions.
     *
     * @param name the topic name to delete
     * @throws IllegalStateException if the topic does not exist
     */
    public void deleteTopic(String name) {
        // 1. look up the topic to get partition count
        Topic topic = topicRepo.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Topic not found: " + name));

        // 2. delete all partition commit logs
        for (int i = 0; i < topic.getPartitionCount(); i++) {
            partitionManager.deletePartition(name, i);
        }

        // 3. remove topic from the repository
        topicRepo.deleteByName(name);

        System.out.println("[TOPIC] Deleted topic '" + name + "' and "
                + topic.getPartitionCount() + " partitions");
    }

    // ===================== Queries =====================

    /**
     * Retrieves a topic by name.
     *
     * @param name the topic name
     * @return the Topic if it exists
     */
    public Optional<Topic> getTopic(String name) {
        return topicRepo.findByName(name);
    }

    /**
     * Returns all registered topics.
     *
     * @return list of all topics
     */
    public List<Topic> getAllTopics() {
        return topicRepo.findAll();
    }

    /**
     * Returns the partition count for a topic.
     *
     * @param topicName the topic name
     * @return number of partitions
     * @throws IllegalStateException if the topic does not exist
     */
    public int getPartitionCount(String topicName) {
        Topic topic = topicRepo.findByName(topicName)
                .orElseThrow(() -> new IllegalStateException("Topic not found: " + topicName));
        return topic.getPartitionCount();
    }
}
