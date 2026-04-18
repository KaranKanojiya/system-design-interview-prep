package com.systemdesign.chat.exception;

public class ConversationNotFoundException extends ChatException {

    public ConversationNotFoundException(String conversationId) {
        super("Conversation not found: " + conversationId);
    }
}
