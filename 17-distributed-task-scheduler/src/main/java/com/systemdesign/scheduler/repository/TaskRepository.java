package com.systemdesign.scheduler.repository;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskStatus;

import java.util.List;
import java.util.Optional;

// Wiring: Storage abstraction for Task entities. InMemoryTaskRepository is the
// default implementation; a real deployment would swap in a JDBC or DynamoDB adapter.
public interface TaskRepository {

    void save(Task task);

    Optional<Task> findById(String id);

    List<Task> findByStatus(TaskStatus status);

    List<Task> findByGroupId(String groupId);

    List<Task> findAll();

    void deleteById(String id);

    boolean existsById(String id);
}
