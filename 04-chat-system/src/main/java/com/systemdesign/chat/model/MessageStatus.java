package com.systemdesign.chat.model;

/**
 * Tracks the lifecycle of a message from sending through read confirmation.
 * Each status carries a visual symbol for console output.
 */
public enum MessageStatus {
    SENDING("⏳"),
    SENT("✓"),
    DELIVERED("✓✓"),
    READ("✓✓(blue)"),
    FAILED("✗");

    private final String symbol;

    MessageStatus(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * A terminal status means no further transitions are expected.
     */
    public boolean isTerminal() {
        return this == READ || this == FAILED;
    }
}
