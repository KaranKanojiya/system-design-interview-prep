package com.systemdesign.filestorage.repository;

import com.systemdesign.filestorage.model.User;

import java.util.List;
import java.util.Optional;

/**
 * UserRepository — data access interface for User entities.
 *
 * Call chain:
 *   UploadService → userRepository.findById(userId) → check quota
 *   AppConfig → userRepository.save(user) during initialization
 */
public interface UserRepository {

    void save(User user);

    Optional<User> findById(String userId);

    List<User> findAll();
}
