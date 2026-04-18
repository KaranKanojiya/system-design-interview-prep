package com.systemdesign.parking.model;

/**
 * Enum representing the current status of a parking spot.
 * Each status has a visual symbol for display board rendering.
 */
public enum SpotStatus {
    AVAILABLE("[ ]"),
    OCCUPIED("[X]"),
    RESERVED("[R]"),
    OUT_OF_ORDER("[!]");

    private final String symbol;

    SpotStatus(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return name() + " " + symbol;
    }
}
