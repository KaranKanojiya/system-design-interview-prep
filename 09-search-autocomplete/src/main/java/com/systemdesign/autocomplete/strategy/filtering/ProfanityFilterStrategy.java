package com.systemdesign.autocomplete.strategy.filtering;

import com.systemdesign.autocomplete.model.Suggestion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ProfanityFilterStrategy — Removes suggestions containing banned/offensive words.
 *
 * HOW IT WORKS:
 *   Maintains a Set of banned words. For each suggestion, checks if the suggestion
 *   text CONTAINS any banned word (substring match, not just exact match).
 *
 *   Example:
 *     Banned words: {"badword", "offensive"}
 *     Suggestion "how to avoid badword" → FILTERED OUT (contains "badword")
 *     Suggestion "how to be inoffensive" → FILTERED OUT (contains "offensive")
 *     Suggestion "how to cook pasta" → KEPT
 *
 * WHY substring match instead of exact match?
 *   Exact match would miss "badword123" or "super badword".
 *   Substring match is more aggressive but catches more variations.
 *   In production, you'd use more sophisticated approaches:
 *     - Regex patterns
 *     - Phonetic matching (to catch "b4dw0rd")
 *     - ML-based content moderation
 *     - Bloom filter for O(1) membership check at scale
 *
 * CHAIN OF RESPONSIBILITY:
 *   This filter can be chained with other filters:
 *     ProfanityFilter → SpamFilter → MinScoreFilter
 *   Each filter receives the output of the previous one.
 *   To chain, AutocompleteService iterates over List<FilterStrategy>.
 *
 * Wiring:
 *   AppConfig → new ProfanityFilterStrategy(bannedWords) → AutocompleteService
 */
public class ProfanityFilterStrategy implements FilterStrategy {

    /** Set of banned words. Stored lowercase for case-insensitive matching. */
    private final Set<String> bannedWords;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Create with a pre-defined set of banned words.
     * All words are lowercased for consistent matching.
     */
    public ProfanityFilterStrategy(Set<String> bannedWords) {
        this.bannedWords = new HashSet<>();
        for (String word : bannedWords) {
            this.bannedWords.add(word.toLowerCase().trim());
        }
    }

    /**
     * Create with default (empty) banned words set.
     * Use addBannedWord() to populate.
     */
    public ProfanityFilterStrategy() {
        this.bannedWords = new HashSet<>();
    }

    // -----------------------------------------------------------------------
    // filter()
    // -----------------------------------------------------------------------

    /**
     * Remove any suggestion that contains a banned word.
     *
     * Algorithm:
     *   For each suggestion:
     *     For each banned word:
     *       If suggestion.text.contains(bannedWord) → exclude this suggestion
     *
     * Time: O(S * B * L) where S = suggestions, B = banned words, L = avg text length
     * In production with large banned lists, you'd use:
     *   - Aho-Corasick algorithm: O(S * L + B) — builds automaton from all banned words
     *   - Trie of banned words for prefix matching
     *   - Bloom filter for quick "definitely not banned" check
     *
     * We return a NEW list (don't modify the input) for immutability.
     */
    @Override
    public List<Suggestion> filter(List<Suggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty() || bannedWords.isEmpty()) {
            return suggestions;
        }

        List<Suggestion> filtered = new ArrayList<>();
        for (Suggestion suggestion : suggestions) {
            if (!containsBannedWord(suggestion.getText())) {
                filtered.add(suggestion);
            }
            // If it contains a banned word, it's silently dropped
        }
        return filtered;
    }

    /**
     * Check if the text contains any banned word (case-insensitive substring match).
     */
    private boolean containsBannedWord(String text) {
        if (text == null) return false;
        String lowerText = text.toLowerCase();
        for (String banned : bannedWords) {
            if (lowerText.contains(banned)) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Management
    // -----------------------------------------------------------------------

    /**
     * Add a word to the banned list.
     */
    public void addBannedWord(String word) {
        if (word != null && !word.isBlank()) {
            bannedWords.add(word.toLowerCase().trim());
        }
    }

    /**
     * Remove a word from the banned list.
     */
    public void removeBannedWord(String word) {
        if (word != null) {
            bannedWords.remove(word.toLowerCase().trim());
        }
    }

    /**
     * Get the count of banned words.
     */
    public int getBannedWordCount() {
        return bannedWords.size();
    }

    @Override
    public String getName() {
        return "ProfanityFilter(" + bannedWords.size() + " banned words)";
    }
}
