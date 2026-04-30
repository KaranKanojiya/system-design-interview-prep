package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.Post;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InMemoryPostRepository — ConcurrentHashMap-backed post storage.
 *
 * Thread-safe via ConcurrentHashMap. In production, this would be
 * Cassandra (partitioned by authorId for efficient per-user queries)
 * or DynamoDB (with postId as partition key, createdAt as sort key).
 */
public class InMemoryPostRepository implements PostRepository {

    private final Map<String, Post> posts = new ConcurrentHashMap<>();

    @Override
    public void save(Post post) {
        posts.put(post.getPostId(), post);
    }

    @Override
    public Optional<Post> findById(String postId) {
        return Optional.ofNullable(posts.get(postId));
    }

    @Override
    public List<Post> findByAuthorId(String authorId) {
        return posts.values().stream()
                .filter(p -> p.getAuthorId().equals(authorId))
                .sorted(Comparator.comparing(Post::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<Post> findRecentByAuthorId(String authorId, int limit) {
        return posts.values().stream()
                .filter(p -> p.getAuthorId().equals(authorId))
                .sorted(Comparator.comparing(Post::getCreatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<Post> findByIds(List<String> postIds) {
        List<Post> result = new ArrayList<>();
        for (String postId : postIds) {
            Post post = posts.get(postId);
            if (post != null) {
                result.add(post);
            }
        }
        return result;
    }

    @Override
    public boolean existsById(String postId) {
        return posts.containsKey(postId);
    }

    @Override
    public long count() {
        return posts.size();
    }
}
