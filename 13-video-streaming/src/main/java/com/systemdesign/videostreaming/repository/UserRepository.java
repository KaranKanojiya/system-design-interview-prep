package com.systemdesign.videostreaming.repository;

import com.systemdesign.videostreaming.model.User;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for User entities.
 * In production: backed by a user service (microservice) with its own DB.
 */
public interface UserRepository {

    void save(User user);

    Optional<User> findById(String userId);

    List<User> findAll();
}
