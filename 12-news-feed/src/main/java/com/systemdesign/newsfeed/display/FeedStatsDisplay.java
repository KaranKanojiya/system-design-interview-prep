package com.systemdesign.newsfeed.display;

import com.systemdesign.newsfeed.repository.EngagementRepository;
import com.systemdesign.newsfeed.repository.UserRepository;
import com.systemdesign.newsfeed.service.FanoutService;
import com.systemdesign.newsfeed.service.FeedService;
import com.systemdesign.newsfeed.service.PostService;
import com.systemdesign.newsfeed.service.SocialGraphService;
import com.systemdesign.newsfeed.strategy.fanout.HybridFanoutStrategy;

/**
 * FeedStatsDisplay — Dashboard-style display of system statistics.
 *
 * Shows overall system health metrics that you'd monitor in production:
 * - Total users, posts, follows (system scale)
 * - Fan-out stats (write path performance)
 * - Feed generation stats (read path performance)
 * - Engagement stats (user activity)
 */
public class FeedStatsDisplay {

    private final UserRepository userRepository;
    private final PostService postService;
    private final SocialGraphService socialGraphService;
    private final FanoutService fanoutService;
    private final FeedService feedService;
    private final EngagementRepository engagementRepository;

    public FeedStatsDisplay(UserRepository userRepository,
                            PostService postService,
                            SocialGraphService socialGraphService,
                            FanoutService fanoutService,
                            FeedService feedService,
                            EngagementRepository engagementRepository) {
        this.userRepository = userRepository;
        this.postService = postService;
        this.socialGraphService = socialGraphService;
        this.fanoutService = fanoutService;
        this.feedService = feedService;
        this.engagementRepository = engagementRepository;
    }

    public void displayStats() {
        System.out.println();
        System.out.println("--- System Statistics ---");
        System.out.printf("   Total Users:           %d%n", userRepository.count());
        System.out.printf("   Total Posts:            %d%n", postService.getPostCount());
        System.out.printf("   Total Follows:          %d%n", socialGraphService.getFollowCount());
        System.out.printf("   Total Likes:            %d%n", engagementRepository.totalLikes());
        System.out.printf("   Total Comments:         %d%n", engagementRepository.totalComments());
        System.out.println();
        System.out.println("--- Fan-out Performance ---");
        System.out.printf("   Total Fan-outs:         %d%n", fanoutService.getTotalFanoutCount());
        System.out.printf("   Total Fan-out Time:     %dms%n", fanoutService.getTotalFanoutTimeMs());
        System.out.printf("   Avg Fan-out Time:       %.2fms%n", fanoutService.getAverageFanoutTimeMs());

        // Show hybrid fan-out stats if available
        if (fanoutService.getFanoutStrategy() instanceof HybridFanoutStrategy hybrid) {
            System.out.printf("   Write Path (normal):    %d%n", hybrid.getWritePathCount());
            System.out.printf("   Read Path (celebrity):  %d%n", hybrid.getReadPathCount());
        }

        System.out.println();
        System.out.println("--- Feed Generation Performance ---");
        System.out.printf("   Total Feed Gens:        %d%n", feedService.getTotalFeedGenCount());
        System.out.printf("   Total Feed Gen Time:    %dms%n", feedService.getTotalFeedGenTimeMs());
        System.out.printf("   Avg Feed Gen Time:      %.2fms%n", feedService.getAverageFeedGenTimeMs());
    }
}
