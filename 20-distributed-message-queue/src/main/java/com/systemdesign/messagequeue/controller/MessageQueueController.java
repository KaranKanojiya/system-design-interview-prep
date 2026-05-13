package com.systemdesign.messagequeue.controller;

import com.systemdesign.messagequeue.model.AckMode;
import com.systemdesign.messagequeue.model.ConsumerRecord;
import com.systemdesign.messagequeue.model.ProducerRecord;
import com.systemdesign.messagequeue.model.Topic;
import com.systemdesign.messagequeue.service.MessageQueueService;
import com.systemdesign.messagequeue.service.MetricsService;

import java.util.List;

// Wiring: REST-like controller delegating to MessageQueueService facade.
public class MessageQueueController {

    private final MessageQueueService mqService;
    private final MetricsService metricsService;

    public MessageQueueController(MessageQueueService mqService, MetricsService metricsService) {
        this.mqService = mqService;
        this.metricsService = metricsService;
    }

    public Topic createTopic(String topicName, int partitions, int replicationFactor) {
        System.out.println("[CONTROLLER] POST /topics — name=" + topicName);
        return mqService.createTopic(topicName, partitions, replicationFactor);
    }

    public long produce(ProducerRecord record, AckMode ackMode) {
        System.out.println("[CONTROLLER] POST /topics/" + record.getTopic() + "/messages");
        return mqService.produce(record, ackMode);
    }

    public List<ConsumerRecord> consume(String groupId, String topic, int partition, int maxMessages) {
        System.out.println("[CONTROLLER] GET /topics/" + topic + "/messages");
        return mqService.consume(groupId, topic, partition, maxMessages);
    }

    public void commit(String groupId, String topicName, int partition, long offset) {
        System.out.println("[CONTROLLER] POST /topics/" + topicName + "/commit");
        mqService.commit(groupId, topicName, partition, offset);
    }

    public void subscribe(String groupId, String consumerId, String topic, int partitionCount) {
        System.out.println("[CONTROLLER] POST /consumer-groups/subscribe");
        mqService.subscribe(groupId, consumerId, topic, partitionCount);
    }

    public void getDashboard() {
        System.out.println("[CONTROLLER] GET /dashboard");
        metricsService.printDashboard();
    }
}
