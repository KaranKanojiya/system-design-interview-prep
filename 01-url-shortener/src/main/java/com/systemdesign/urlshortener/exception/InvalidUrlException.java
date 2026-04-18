package com.systemdesign.urlshortener.exception;

public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException(String url) {
        super("Invalid URL format: " + url);
    }
}
