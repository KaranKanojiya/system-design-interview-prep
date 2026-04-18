package com.systemdesign.urlshortener.strategy;

import java.security.SecureRandom;

/**
 * Generates a cryptographically random alphanumeric short code.
 *
 * Trade-offs:
 * - No counter dependency — works well in distributed systems
 * - Collision risk requires a check-and-retry loop
 * - Uses SecureRandom (not ThreadLocalRandom) for unpredictability
 */
public class RandomEncodingStrategy implements EncodingStrategy {

    private static final String ALPHANUMERIC = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 7;
    private final SecureRandom random = new SecureRandom();

    @Override
    public String encode(String input) {
        // Input is ignored — code is purely random
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return code.toString();
    }

    @Override
    public String name() {
        return "Random";
    }
}
