package com.systemdesign.urlshortener.repository;

import com.systemdesign.urlshortener.model.Url;

import java.util.Optional;

/**
 * Repository abstraction for URL persistence.
 * In production, this would be backed by a database (e.g., DynamoDB, Redis, PostgreSQL).
 * The interface allows swapping implementations without changing service logic.
 */
public interface UrlRepository {

    Optional<Url> findByShortCode(String shortCode);

    Url save(Url url);

    void deleteByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    long count();
}
