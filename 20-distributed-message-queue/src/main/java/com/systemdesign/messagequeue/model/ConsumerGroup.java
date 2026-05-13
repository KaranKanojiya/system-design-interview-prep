package com.systemdesign.messagequeue.model;

import java.time.Instant;
import java.util.*;

/**
 * Consumer group — coordinates a set of consumers for load-balanced consumption.
 * Each partition is assigned to exactly one consumer within the group (Kafka-style).
 */
public class ConsumerGroup {

    // --- identity ---
    private final String groupId;                                        // unique group name

    // --- subscriptions ---
    private final Set<String> subscribedTopics;                          // topics this group reads from

    // --- membership ---
    private final Map<String, ConsumerInstance> members;                 // consumerId -> instance
    private final Map<String, List<PartitionAssignment>> assignments;   // consumerId -> assigned partitions

    // --- metadata ---
    private final Instant createdAt;

    public ConsumerGroup(String groupId) {
        this.groupId = groupId;
        this.subscribedTopics = new HashSet<>();
        this.members = new HashMap<>();
        this.assignments = new HashMap<>();
        this.createdAt = Instant.now();
    }

    // --- getters ---

    public String getGroupId() { return groupId; }
    public Set<String> getSubscribedTopics() { return subscribedTopics; }
    public Map<String, ConsumerInstance> getMembers() { return members; }
    public Map<String, List<PartitionAssignment>> getAssignments() { return assignments; }
    public Instant getCreatedAt() { return createdAt; }

    // --- topic subscription ---

    public void subscribe(String topic) {
        subscribedTopics.add(topic);
    }

    public void unsubscribe(String topic) {
        subscribedTopics.remove(topic);
    }

    // --- membership management ---

    /** Adds a consumer instance to the group (triggers rebalance in the service layer). */
    public void addMember(ConsumerInstance instance) {
        members.put(instance.getConsumerId(), instance);
        assignments.put(instance.getConsumerId(), new ArrayList<>());
    }

    /** Removes a consumer from the group (triggers rebalance in the service layer). */
    public void removeMember(String consumerId) {
        members.remove(consumerId);
        assignments.remove(consumerId);
    }

    /** Returns the number of active consumers in this group. */
    public int getMemberCount() {
        return members.size();
    }

    /** Sets the partition assignments for a specific consumer. */
    public void setAssignment(String consumerId, List<PartitionAssignment> partitions) {
        assignments.put(consumerId, new ArrayList<>(partitions));
    }

    @Override
    public String toString() {
        return "ConsumerGroup{groupId='" + groupId + "', members=" + members.size()
                + ", topics=" + subscribedTopics + "}";
    }
}
