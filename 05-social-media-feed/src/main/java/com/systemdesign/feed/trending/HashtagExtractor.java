package com.systemdesign.feed.trending;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts hashtags from tweet content.
 */
public class HashtagExtractor {

    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#(\\w+)");

    /**
     * Extracts unique, lowercased hashtags from content.
     *
     * @param content the tweet text
     * @return list of unique hashtags (without the # prefix)
     */
    public static List<String> extract(String content) {
        List<String> tags = new ArrayList<>();
        if (content == null || content.isBlank()) return tags;

        Matcher matcher = HASHTAG_PATTERN.matcher(content);
        while (matcher.find()) {
            String tag = matcher.group(1).toLowerCase();
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }
}
