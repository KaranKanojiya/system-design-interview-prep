package com.systemdesign.feed.repository;

import com.systemdesign.feed.model.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    void save(User user);

    Optional<User> findById(String userId);

    Optional<User> findByUsername(String username);

    List<User> findAll();
}
