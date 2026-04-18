package com.systemdesign.chat.repository;

import com.systemdesign.chat.model.User;
import com.systemdesign.chat.model.UserStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory user store.
 */
public class InMemoryUserRepository implements UserRepository {

    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();

    @Override
    public void save(User user) {
        users.put(user.getUserId(), user);
    }

    @Override
    public Optional<User> findById(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    @Override
    public void updateStatus(String userId, UserStatus status) {
        User user = users.get(userId);
        if (user != null) {
            if (status == UserStatus.ONLINE) {
                user.goOnline();
            } else {
                user.goOffline();
            }
        }
    }

    @Override
    public List<User> findAll() {
        return List.copyOf(users.values());
    }
}
