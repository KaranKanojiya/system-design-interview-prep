package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryUserRepository — ConcurrentHashMap-backed user storage.
 *
 * Thread-safe via ConcurrentHashMap. In production, this would be
 * replaced by a JPA repository or a call to a User microservice.
 */
public class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> users = new ConcurrentHashMap<>();

    @Override
    public void save(User user) {
        users.put(user.getUserId(), user);
    }

    @Override
    public Optional<User> findById(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    @Override
    public List<User> findAll() {
        return new ArrayList<>(users.values());
    }

    @Override
    public boolean existsById(String userId) {
        return users.containsKey(userId);
    }

    @Override
    public void deleteById(String userId) {
        users.remove(userId);
    }

    @Override
    public long count() {
        return users.size();
    }
}
