package com.systemdesign.chat.repository;

import com.systemdesign.chat.model.User;
import com.systemdesign.chat.model.UserStatus;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for users.
 */
public interface UserRepository {

    void save(User user);

    Optional<User> findById(String userId);

    void updateStatus(String userId, UserStatus status);

    List<User> findAll();
}
