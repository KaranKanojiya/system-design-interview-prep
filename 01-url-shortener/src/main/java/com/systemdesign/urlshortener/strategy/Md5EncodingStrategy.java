package com.systemdesign.urlshortener.strategy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates a short code by MD5-hashing the original URL and taking the first 7 hex chars.
 *
 * Trade-offs:
 * - Same URL always produces the same code (idempotent / deduplication-friendly)
 * - Collision risk: 16^7 = ~268M unique codes from 7 hex chars
 * - Not suitable for high-volume systems without collision handling
 */
public class Md5EncodingStrategy implements EncodingStrategy {

    private static final int CODE_LENGTH = 7;

    @Override
    public String encode(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());

            // Convert bytes to hex string
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }

            return hex.substring(0, CODE_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @Override
    public String name() {
        return "MD5";
    }
}
