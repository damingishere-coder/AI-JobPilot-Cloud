package com.getjobs.cloud.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the delivery task state machine and plugin execution.
 * All bounds are clamped on set like the other cloud property classes.
 */
@Validated
@Profile("api")
@ConfigurationProperties(prefix = "app.delivery")
public class DeliveryProperties {

    /** Plugin execution lease length in seconds (bounded 30-1800). */
    private int leaseSeconds = 600;

    /** Maximum plugin execution attempts per task (bounded 1-10). */
    private int maxAttempts = 3;

    /** Default page size of the plugin pending list. */
    private int pendingDefaultLimit = 10;

    /** Maximum page size of the plugin pending list. */
    private int pendingMaxLimit = 20;

    /** Backoff hint returned to the extension after an empty poll. */
    private int pollAfterSeconds = 10;

    /** Maximum greeting length in Unicode code points (Boss only). */
    private int greetingMaxCodePoints = 60;

    /** Delay of the scheduled lease-expiry sweep. */
    private String leaseSweepDelay = "60s";

    /**
     * P6 master switch for plugin execution. Off by default: confirmation
     * stops at CONFIRMED and the plugin task endpoints (pending/start/success/
     * fail/pause) answer a safe EXECUTION_DISABLED error instead of dispatching
     * or mutating anything. Historical execution tests enable it explicitly.
     */
    private boolean executionEnabled = false;

    public boolean isExecutionEnabled() {
        return executionEnabled;
    }

    public void setExecutionEnabled(boolean executionEnabled) {
        this.executionEnabled = executionEnabled;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = Math.max(30, Math.min(leaseSeconds, 1800));
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 10));
    }

    public int getPendingDefaultLimit() {
        return pendingDefaultLimit;
    }

    public void setPendingDefaultLimit(int pendingDefaultLimit) {
        this.pendingDefaultLimit = Math.max(1, Math.min(pendingDefaultLimit, pendingMaxLimit));
    }

    public int getPendingMaxLimit() {
        return pendingMaxLimit;
    }

    public void setPendingMaxLimit(int pendingMaxLimit) {
        this.pendingMaxLimit = Math.max(1, Math.min(pendingMaxLimit, 50));
    }

    public int getPollAfterSeconds() {
        return pollAfterSeconds;
    }

    public void setPollAfterSeconds(int pollAfterSeconds) {
        this.pollAfterSeconds = Math.max(1, Math.min(pollAfterSeconds, 3600));
    }

    public int getGreetingMaxCodePoints() {
        return greetingMaxCodePoints;
    }

    public void setGreetingMaxCodePoints(int greetingMaxCodePoints) {
        this.greetingMaxCodePoints = Math.max(1, Math.min(greetingMaxCodePoints, 500));
    }

    public String getLeaseSweepDelay() {
        return leaseSweepDelay;
    }

    public void setLeaseSweepDelay(String leaseSweepDelay) {
        this.leaseSweepDelay = leaseSweepDelay;
    }
}
