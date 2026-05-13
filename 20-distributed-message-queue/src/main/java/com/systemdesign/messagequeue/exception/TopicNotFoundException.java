package com.systemdesign.messagequeue.exception;

/**
 * Thrown when a referenced topic does not exist in the topic registry.
 *
 * Flow: ProducerService.send() / ConsumerService.poll() → TopicRepository.findByName()
 *       → empty → TopicNotFoundException
 */
public class TopicNotFoundException extends MessageQueueException {

    private final String topicName; // the missing topic name

    public TopicNotFoundException(String topicName) {
        super("Topic not found: " + topicName);
        this.topicName = topicName;
    }

    public String getTopicName() {
        return topicName;
    }

    @Override
    public String getMessage() {
        return "Topic not found: " + topicName;
    }
}
