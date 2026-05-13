package com.systemdesign.messagequeue.service;

import com.systemdesign.messagequeue.engine.CommitLog;
import com.systemdesign.messagequeue.engine.PartitionManager;
import com.systemdesign.messagequeue.engine.ReplicationEngine;
import com.systemdesign.messagequeue.model.AckMode;
import com.systemdesign.messagequeue.model.Message;
import com.systemdesign.messagequeue.model.Partition;
import com.systemdesign.messagequeue.model.ProducerRecord;
import com.systemdesign.messagequeue.model.Topic;
import com.systemdesign.messagequeue.repository.TopicRepository;
import com.systemdesign.messagequeue.strategy.partitioning.PartitioningStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Message production — routes ProducerRecords to the correct partition and appends to CommitLog.
 *
 * Flow (send):
 *  1. Resolve the target partition (explicit > key-hash > round-robin)
 *  2. Build a Message from the ProducerRecord
 *  3. Append to the partition's CommitLog (assigns offset)
 *  4. Replicate to ISR replicas based on AckMode
 *  5. Return the assigned offset
 */
public class ProducerService {

    // --- dependencies (constructor-injected) ---
    private final PartitionManager partitionManager;          // partition commit log access
    private final PartitioningStrategy partitioningStrategy;  // Strategy Pattern — partition assignment
    private final ReplicationEngine replicationEngine;        // handles ISR replication
    private final TopicRepository topicRepo;                  // topic metadata lookup

    public ProducerService(PartitionManager partitionManager,
                           PartitioningStrategy partitioningStrategy,
                           ReplicationEngine replicationEngine,
                           TopicRepository topicRepo) {
        this.partitionManager = partitionManager;
        this.partitioningStrategy = partitioningStrategy;
        this.replicationEngine = replicationEngine;
        this.topicRepo = topicRepo;
    }

    // ===================== Send =====================

    /**
     * Sends a single producer record to the message queue.
     *
     * @param record  the producer record containing topic, key, value, and optional partition
     * @param ackMode the acknowledgment mode (NONE, LEADER, ALL)
     * @return the offset assigned by the CommitLog
     * @throws IllegalStateException if the topic or resolved partition does not exist
     */
    public long send(ProducerRecord record, AckMode ackMode) {
        String topicName = record.getTopic();

        // 1. look up the topic to get partition count
        Topic topic = topicRepo.findByName(topicName)
                .orElseThrow(() -> new IllegalStateException("Topic not found: " + topicName));

        int partitionCount = topic.getPartitionCount();

        // 2. resolve the target partition
        int targetPartition = resolvePartition(record, partitionCount);

        // 3. get the CommitLog for this partition
        CommitLog commitLog = partitionManager.getPartition(topicName, targetPartition)
                .orElseThrow(() -> new IllegalStateException(
                        "Partition not found: " + topicName + "-" + targetPartition));

        // 4. build the Message from the ProducerRecord
        Message message = new Message.Builder(topicName, record.getValue())
                .key(record.getKey())
                .partition(targetPartition)
                .headers(record.getHeaders())
                .build();

        // 5. append to the CommitLog — assigns monotonic offset
        long offset = commitLog.append(message);

        // 6. replicate to ISR replicas based on ack mode
        Partition partition = new Partition(topicName, targetPartition, "broker-0", List.of("broker-0"));
        replicationEngine.replicate(message, partition, ackMode);

        System.out.println("[PRODUCER] Sent message to " + topicName + "-" + targetPartition
                + " at offset=" + offset + " (ack=" + ackMode + ", key=" + record.getKey() + ")");

        return offset;
    }

    /**
     * Sends a batch of producer records. Each record is sent individually.
     *
     * @param records list of producer records
     * @param ackMode the acknowledgment mode for all records
     * @return list of offsets in the same order as the input records
     */
    public List<Long> sendBatch(List<ProducerRecord> records, AckMode ackMode) {
        List<Long> offsets = new ArrayList<>();
        for (ProducerRecord record : records) {
            offsets.add(send(record, ackMode));
        }
        System.out.println("[PRODUCER] Batch sent " + records.size() + " messages");
        return offsets;
    }

    // ===================== Partitioning =====================

    /**
     * Determines the partition index for a given key using the configured strategy.
     *
     * @param key            the message key
     * @param partitionCount total number of partitions
     * @return the partition index in [0, partitionCount)
     */
    public int getPartitionForKey(String key, int partitionCount) {
        return partitioningStrategy.assignPartition(key, partitionCount);
    }

    // ===================== Internal =====================

    /**
     * Resolves the target partition for a ProducerRecord.
     * Priority: explicit partition > key-based hash > round-robin (null key).
     */
    private int resolvePartition(ProducerRecord record, int partitionCount) {
        // explicit partition takes precedence
        if (record.getPartition() != null) {
            int explicit = record.getPartition();
            if (explicit < 0 || explicit >= partitionCount) {
                throw new IllegalArgumentException(
                        "Partition " + explicit + " out of range [0, " + partitionCount + ")");
            }
            return explicit;
        }

        // delegate to the partitioning strategy (handles key-hash and round-robin)
        return partitioningStrategy.assignPartition(record.getKey(), partitionCount);
    }
}
