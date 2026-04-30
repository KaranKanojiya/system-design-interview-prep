package com.systemdesign.payment.model;

/**
 * Currency — Supported currencies with display metadata.
 *
 * Each currency knows its symbol and how many decimal places it uses.
 * JPY is zero-decimal (100 yen, not 1.00 yen) — this matters for
 * amount formatting and rounding in the LedgerService.
 *
 * WHY store decimalPlaces?
 *   Stripe does the same thing — they store amounts in the smallest
 *   currency unit (cents for USD, yen for JPY).  We keep it as double
 *   for simplicity but the decimalPlaces field is used for display.
 */
public enum Currency {

    USD("$", 2),
    EUR("€", 2),
    GBP("£", 2),
    INR("₹", 2),
    JPY("¥", 0);

    private final String symbol;
    private final int decimalPlaces;

    Currency(String symbol, int decimalPlaces) {
        this.symbol = symbol;
        this.decimalPlaces = decimalPlaces;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    /**
     * Format an amount with this currency's symbol and decimal places.
     * e.g. USD 99.5 → "$99.50", JPY 100 → "¥100"
     */
    public String format(double amount) {
        if (decimalPlaces == 0) {
            return symbol + String.format("%.0f", amount);
        }
        return symbol + String.format("%." + decimalPlaces + "f", amount);
    }
}
