package com.systemdesign.payment.service;

import com.systemdesign.payment.model.Currency;

import java.util.HashMap;
import java.util.Map;

/**
 * CurrencyService — Currency conversion using exchange rates.
 *
 * In production:
 *   - Rates would come from a real-time feed (Open Exchange Rates, ECB, etc.)
 *   - Rates would be cached with short TTL (1-5 minutes)
 *   - Spread/markup would be applied for the platform's revenue
 *
 * Here we use static rates for simplicity.
 * All rates are relative to USD (USD is the base currency).
 */
public class CurrencyService {

    // Exchange rates: 1 USD = X of target currency
    // These are approximate real-world rates (not live)
    private final Map<String, Double> exchangeRates = new HashMap<>();

    public CurrencyService() {
        // USD → other currencies
        exchangeRates.put("USD_EUR", 0.92);
        exchangeRates.put("USD_GBP", 0.79);
        exchangeRates.put("USD_INR", 83.50);
        exchangeRates.put("USD_JPY", 149.50);

        // EUR → other currencies
        exchangeRates.put("EUR_USD", 1.09);
        exchangeRates.put("EUR_GBP", 0.86);
        exchangeRates.put("EUR_INR", 90.85);
        exchangeRates.put("EUR_JPY", 162.55);

        // GBP → other currencies
        exchangeRates.put("GBP_USD", 1.27);
        exchangeRates.put("GBP_EUR", 1.16);
        exchangeRates.put("GBP_INR", 105.73);
        exchangeRates.put("GBP_JPY", 189.25);

        // INR → other currencies
        exchangeRates.put("INR_USD", 0.012);
        exchangeRates.put("INR_EUR", 0.011);
        exchangeRates.put("INR_GBP", 0.0095);
        exchangeRates.put("INR_JPY", 1.79);

        // JPY → other currencies
        exchangeRates.put("JPY_USD", 0.0067);
        exchangeRates.put("JPY_EUR", 0.0062);
        exchangeRates.put("JPY_GBP", 0.0053);
        exchangeRates.put("JPY_INR", 0.56);
    }

    /**
     * Convert an amount from one currency to another.
     *
     * @param amount the amount in the source currency
     * @param from   source currency
     * @param to     target currency
     * @return the converted amount in the target currency
     */
    public double convert(double amount, Currency from, Currency to) {
        if (from == to) {
            return amount; // No conversion needed
        }

        String key = from.name() + "_" + to.name();
        Double rate = exchangeRates.get(key);

        if (rate == null) {
            throw new IllegalArgumentException(
                "No exchange rate available for " + from + " → " + to);
        }

        double converted = amount * rate;

        System.out.println("    [CurrencyService] " + from.format(amount)
                           + " → " + to.format(converted)
                           + " (rate: " + rate + ")");

        return converted;
    }

    /**
     * Get the exchange rate between two currencies.
     */
    public double getRate(Currency from, Currency to) {
        if (from == to) return 1.0;
        String key = from.name() + "_" + to.name();
        Double rate = exchangeRates.get(key);
        if (rate == null) {
            throw new IllegalArgumentException("No rate for " + from + " → " + to);
        }
        return rate;
    }
}
