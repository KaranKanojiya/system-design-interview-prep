package com.systemdesign.scheduler.strategy.scheduling;

import com.systemdesign.scheduler.engine.CronParser;
import com.systemdesign.scheduler.model.CronSchedule;
import com.systemdesign.scheduler.model.Task;

import java.time.Instant;
import java.util.Optional;

// Wiring: Delegates cron expression parsing to CronParser from the engine layer.
// Parses the task's cron expression into a CronSchedule, then checks if it matches
// the current time and computes the next fire time.
public class CronSchedulingStrategy implements SchedulingStrategy {

    private final CronParser cronParser;

    public CronSchedulingStrategy(CronParser cronParser) {
        this.cronParser = cronParser;
    }

    @Override
    public boolean shouldScheduleNow(Task task, Instant currentTime) {
        String cronExpression = task.getCronExpression();
        if (cronExpression == null || cronExpression.isBlank()) {
            return false;
        }
        // Parse the expression and check if current time matches
        CronSchedule schedule = cronParser.parse(cronExpression);
        return schedule.matches(currentTime);
    }

    @Override
    public Optional<Instant> getNextScheduleTime(Task task, Instant currentTime) {
        String cronExpression = task.getCronExpression();
        if (cronExpression == null || cronExpression.isBlank()) {
            return Optional.empty();
        }
        CronSchedule schedule = cronParser.parse(cronExpression);
        return cronParser.getNextFireTime(schedule, currentTime);
    }

    @Override
    public String getStrategyName() {
        return "CRON";
    }
}
