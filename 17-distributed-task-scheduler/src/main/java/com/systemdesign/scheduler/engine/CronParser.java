package com.systemdesign.scheduler.engine;

import com.systemdesign.scheduler.model.CronSchedule;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

// Wiring: Parses 5-field cron expressions ("minute hour dayOfMonth month dayOfWeek")
// and calculates the next fire time. Used by SchedulerEngine.tick() to decide
// when recurring CRON tasks should be re-enqueued.
// Note: This is a simplified simulation parser — not a production cron engine.
public class CronParser {

    private static final int CRON_FIELD_COUNT = 5;
    private static final int MAX_SCAN_MINUTES = 24 * 60; // scan up to 24 hours ahead

    // Parses a cron expression string into a CronSchedule model
    public CronSchedule parse(String expression) {
        if (!isValid(expression)) {
            throw new IllegalArgumentException("Invalid cron expression: " + expression);
        }
        return new CronSchedule(expression.trim());
    }

    // Calculates the next fire time after the given instant by scanning minute-by-minute
    public Optional<Instant> getNextFireTime(CronSchedule schedule, Instant after) {
        ZonedDateTime current = after.atZone(ZoneOffset.UTC).plusMinutes(1)
                .withSecond(0).withNano(0); // start at next whole minute

        for (int i = 0; i < MAX_SCAN_MINUTES; i++) {
            if (matches(schedule, current)) {
                return Optional.of(current.toInstant());
            }
            current = current.plusMinutes(1);
        }

        // No match within 24h window
        return Optional.empty();
    }

    // Validates that the expression has 5 fields, each being '*' or a number in valid range
    public boolean isValid(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != CRON_FIELD_COUNT) {
            return false;
        }
        // field index -> [min, max] valid range
        int[][] ranges = {
                {0, 59},  // minute
                {0, 23},  // hour
                {1, 31},  // day of month
                {1, 12},  // month
                {0, 7}    // day of week (0 and 7 both = Sunday)
        };
        for (int i = 0; i < CRON_FIELD_COUNT; i++) {
            if (!isValidField(parts[i], ranges[i][0], ranges[i][1])) {
                return false;
            }
        }
        return true;
    }

    // --- Internal helpers ---

    private boolean isValidField(String field, int min, int max) {
        if ("*".equals(field)) {
            return true;
        }
        try {
            int val = Integer.parseInt(field);
            return val >= min && val <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Checks whether a ZonedDateTime matches all 5 cron fields
    private boolean matches(CronSchedule schedule, ZonedDateTime dt) {
        return matchesField(schedule.getMinute(), dt.getMinute())
                && matchesField(schedule.getHour(), dt.getHour())
                && matchesField(schedule.getDayOfMonth(), dt.getDayOfMonth())
                && matchesField(schedule.getMonth(), dt.getMonthValue())
                && matchesDayOfWeek(schedule.getDayOfWeek(), dt.getDayOfWeek().getValue() % 7);
    }

    private boolean matchesField(String field, int actual) {
        if ("*".equals(field)) {
            return true;
        }
        return Integer.parseInt(field) == actual;
    }

    // Day of week: cron uses 0=Sun..6=Sat (and 7=Sun alias).
    // Java DayOfWeek.getValue() returns 1=Mon..7=Sun, so we mod 7 to normalize.
    private boolean matchesDayOfWeek(String field, int actual) {
        if ("*".equals(field)) {
            return true;
        }
        int cronDow = Integer.parseInt(field);
        // Normalize 7 -> 0 (both mean Sunday)
        if (cronDow == 7) {
            cronDow = 0;
        }
        return cronDow == actual;
    }
}
