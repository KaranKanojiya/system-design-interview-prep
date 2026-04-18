package com.systemdesign.notification.exception;

/**
 * Thrown when a referenced notification template does not exist.
 */
public class TemplateNotFoundException extends NotificationException {

    public TemplateNotFoundException(String message) {
        super(message);
    }
}
