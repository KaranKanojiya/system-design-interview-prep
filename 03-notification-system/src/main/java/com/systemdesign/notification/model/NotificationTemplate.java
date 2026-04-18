package com.systemdesign.notification.model;

import java.util.List;

/**
 * Reusable notification template with placeholder variables.
 * Templates are channel-specific and define required variables for validation.
 */
public class NotificationTemplate {

    private final String id;
    private final String name;
    private final Channel channel;
    private final String subjectTemplate;
    private final String bodyTemplate;
    private final List<String> requiredVariables;

    public NotificationTemplate(String id, String name, Channel channel,
                                String subjectTemplate, String bodyTemplate,
                                List<String> requiredVariables) {
        this.id = id;
        this.name = name;
        this.channel = channel;
        this.subjectTemplate = subjectTemplate;
        this.bodyTemplate = bodyTemplate;
        this.requiredVariables = List.copyOf(requiredVariables);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Channel getChannel() { return channel; }
    public String getSubjectTemplate() { return subjectTemplate; }
    public String getBodyTemplate() { return bodyTemplate; }
    public List<String> getRequiredVariables() { return requiredVariables; }
}
