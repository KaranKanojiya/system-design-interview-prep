package com.systemdesign.urlshortener.strategy;

/**
 * Encodes a numeric counter value into a Base62 short code.
 *
 * Base62 uses: 0-9 (10) + a-z (26) + A-Z (26) = 62 characters.
 * With 7 chars, we get 62^7 = ~3.5 trillion unique codes — plenty for most systems.
 *
 * This is the most common approach in real URL shorteners (e.g., bit.ly).
 * Key trade-off: requires a globally unique counter (single point of contention),
 * but guarantees no collisions.
 */
public class Base62EncodingStrategy implements EncodingStrategy {

    private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 7;

    @Override
    public String encode(String input) {
        long number = Long.parseLong(input);
        StringBuilder encoded = new StringBuilder();

        // Convert decimal to base62
        while (number > 0) {
            int remainder = (int) (number % 62);
            encoded.append(BASE62_CHARS.charAt(remainder));
            number /= 62;
        }

        // Pad to ensure minimum length
        while (encoded.length() < CODE_LENGTH) {
            encoded.append('0');
        }

        // Reverse to get most-significant digit first
        return encoded.reverse().toString().substring(0, CODE_LENGTH);
    }

    @Override
    public String name() {
        return "Base62";
    }
}
