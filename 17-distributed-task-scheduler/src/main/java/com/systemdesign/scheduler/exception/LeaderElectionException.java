package com.systemdesign.scheduler.exception;

// Wiring: Thrown by LeaderElectionService when a node fails to acquire
// or maintain leadership. Triggers failover handling in FailoverService.
public class LeaderElectionException extends SchedulerException {

    public LeaderElectionException(String message) {
        super(message);
    }
}
