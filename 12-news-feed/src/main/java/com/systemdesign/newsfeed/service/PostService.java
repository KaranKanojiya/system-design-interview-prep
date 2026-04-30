package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.exception.PostNotFoundException;
import com.systemdesign.newsfeed.exception.UserNotFoundException;
import com.systemdesign.newsfeed.model.ContentType;
import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.PostRepository;
import com.systemdesign.newsfeed.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PostService — Handles post creation and retrieval.
 *
 * Design notes for interview:
 * - createPost is the WRITE PATH entry point. The call chain is:
 *   PostService.createPost()
 *     -> PostRepository.save(post)           [persist the post]
 *     -> User.incrementPostCount()           [denormalized counter]
 *     -> FanoutService.distribute(post, author)  [push to followers or no-op]
 *
 * - In production, the post save and fan-out would be in separate transactions:
 *   1. Save post to DB (sync, returns immediately).
 *   2. Publish "post created" event to Kafka (async fan-out).
 *   This ensures the user sees their post immediately, while fan-out
 *   happens in the background (eventual consistency for followers' feeds).
 */
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final FanoutService fanoutService;

    public PostService(PostRepository postRepository,
                       UserRepository userRepository,
                       FanoutService fanoutService) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.fanoutService = fanoutService;
    }

    /**
     * Create a new post and trigger fan-out to followers.
     *
     * @param userId      the author's user ID
     * @param content     the post text content
     * @param contentType the type of content (TEXT, IMAGE, VIDEO, etc.)
     * @param mediaUrl    optional media URL (null for text-only posts)
     * @return the created Post
     */
    public Post createPost(String userId, String content, ContentType contentType, String mediaUrl) {
        // Validate author exists
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // Build the post
        Post post = new Post.Builder()
                .postId(UUID.randomUUID().toString().substring(0, 8))
                .authorId(userId)
                .authorName(author.getName())
                .content(content)
                .contentType(contentType)
                .mediaUrl(mediaUrl)
                .createdAt(LocalDateTime.now())
                .build();

        // Save to repository
        postRepository.save(post);

        // Update author's post count
        author.incrementPostCount();

        System.out.printf("   [PostService] Created post '%s' by '%s' (type=%s)%n",
                post.getPostId(), author.getName(), contentType);

        // Trigger fan-out (push to followers or no-op for celebrities)
        fanoutService.distribute(post, author);

        return post;
    }

    /**
     * Create a post with a specific timestamp (for testing/demo purposes).
     */
    public Post createPostWithTimestamp(String userId, String content, ContentType contentType,
                                        String mediaUrl, LocalDateTime createdAt) {
        User author = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        Post post = new Post.Builder()
                .postId(UUID.randomUUID().toString().substring(0, 8))
                .authorId(userId)
                .authorName(author.getName())
                .content(content)
                .contentType(contentType)
                .mediaUrl(mediaUrl)
                .createdAt(createdAt)
                .build();

        postRepository.save(post);
        author.incrementPostCount();

        // Trigger fan-out
        fanoutService.distribute(post, author);

        return post;
    }

    public Post getPost(String postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException(postId));
    }

    public List<Post> getPostsByUser(String userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        return postRepository.findByAuthorId(userId);
    }

    public long getPostCount() {
        return postRepository.count();
    }
}
