package com.systemdesign.scheduler.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

// Wiring: Lightweight cron parser for CRON-type tasks.
// Used by CronTrigger to determine when a recurring task should fire.
// Supports standard 5-field cron format: minute hour dayOfMonth month dayOfWeek.
public class CronSchedule {

    private final String minute;
    private final String hour;
    private final String dayOfMonth;
    private final String month;
    private final String dayOfWeek;
    private final String expression;

    public CronSchedule(String expression) {
        this.expression = expression;
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "Cron expression must have 5 fields (minute hour dayOfMonth month dayOfWeek): " + expression);
        }
        this.minute = parts[0];
        this.hour = parts[1];
        this.dayOfMonth = parts[2];
        this.month = parts[3];
        this.dayOfWeek = parts[4];
    }

    /**
     * Checks if the given instant matches this cron schedule.
     * Uses simple matching: "*" matches any value, a specific number
     * matches exactly, and comma-separated values match any in the list.
     */
    public boolean matches(Instant now) {
        ZonedDateTime dt = now.atZone(ZoneOffset.UTC);
        return fieldMatches(minute, dt.getMinute())
                && fieldMatches(hour, dt.getHour())
                && fieldMatches(dayOfMonth, dt.getDayOfMonth())
                && fieldMatches(month, dt.getMonthValue())
                && fieldMatches(dayOfWeek, dt.getDayOfWeek().getValue() % 7); // 0=Sunday
    }

    private boolean fieldMatches(String field, int value) {
        if ("*".equals(field)) {
            return true;
        }
        // Support comma-separated values: "1,15,30"
        String[] parts = field.split(",");
        for (String part : parts) {
            try {
                if (Integer.parseInt(part.trim()) == value) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // Skip unparseable segments
            }
        }
        return false;
    }

    // --- Getters ---

    public String getMinute() { return minute; }
    public String getHour() { return hour; }
    public String getDayOfMonth() { return dayOfMonth; }
    public String getMonth() { return month; }
    public String getDayOfWeek() { return dayOfWeek; }
    public String getExpression() { return expression; }

    @Override
    public String toString() {
        return expression;
    }
}
