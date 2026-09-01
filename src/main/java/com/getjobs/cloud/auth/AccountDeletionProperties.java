package com.getjobs.cloud.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.account-deletion")
@Profile("api")
public class AccountDeletionProperties {
    private boolean workerEnabled = true;
    private String pollDelay = "60s";
    private int leaseSeconds = 300;
    private int maxAttempts = 1440;
    private Duration backupRetention = Duration.ofDays(180);

    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
    public String getPollDelay() { return pollDelay; }
    public void setPollDelay(String pollDelay) { this.pollDelay = pollDelay; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = Math.max(30, Math.min(leaseSeconds, 3600)); }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = Math.max(1, Math.min(maxAttempts, 2000)); }
    public Duration getBackupRetention() { return backupRetention; }
    public void setBackupRetention(Duration backupRetention) { this.backupRetention = backupRetention; }
}
