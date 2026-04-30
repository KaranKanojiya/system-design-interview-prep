package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.Post;

import java.util.List;
import java.util.Optional;

/**
 * PostRepository — Data access interface for Post entities.
 *
 * In production: backed by a NoSQL store (Cassandra, DynamoDB) for
 * high write throughput and flexible schema, or a relational DB
 * with read replicas for query flexibility.
 */
public interface PostRepository {

    void save(Post post);

    Optional<Post> findById(String postId);

    List<Post> findByAuthorId(String authorId);

    /**
     * Find recent posts by author, limited and sorted newest first.
     * Used by fan-out-on-read to pull celebrity posts.
     */
    List<Post> findRecentByAuthorId(String authorId, int limit);

    /**
     * Find multiple posts by their IDs.
     * Used to hydrate postIds from the timeline cache into full Post objects.
     */
    List<Post> findByIds(List<String> postIds);

    boolean existsById(String postId);

    long count();
}
