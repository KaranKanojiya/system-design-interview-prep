package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.User;

import java.util.List;
import java.util.Optional;

/**
 * UserRepository — Data access interface for User entities.
 *
 * In production: backed by a relational database (PostgreSQL, MySQL)
 * or a user service with its own data store.
 */
public interface UserRepository {

    void save(User user);

    Optional<User> findById(String userId);

    List<User> findAll();

    boolean existsById(String userId);

    void deleteById(String userId);

    long count();
}
