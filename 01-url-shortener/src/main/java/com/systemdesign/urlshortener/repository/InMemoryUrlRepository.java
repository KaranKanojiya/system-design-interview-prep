package com.systemdesign.urlshortener.repository;

import com.systemdesign.urlshortener.model.Url;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of UrlRepository.
 * Uses ConcurrentHashMap for lock-free reads and safe concurrent writes.
 * In production, this would be replaced with a persistent store.
 */
public class InMemoryUrlRepository implements UrlRepository {

    private final ConcurrentHashMap<String, Url> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Url> findByShortCode(String shortCode) {
        return Optional.ofNullable(store.get(shortCode));
    }

    @Override
    public Url save(Url url) {
        store.put(url.getShortCode(), url);
        return url;
    }

    @Override
    public void deleteByShortCode(String shortCode) {
        store.remove(shortCode);
    }

    @Override
    public boolean existsByShortCode(String shortCode) {
        return store.containsKey(shortCode);
    }

    @Override
    public long count() {
        return store.size();
    }
}
