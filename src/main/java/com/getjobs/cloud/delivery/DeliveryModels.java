package com.getjobs.cloud.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API models for the Web delivery list and the plugin execution endpoints.
 * Plugin responses carry only the minimum fields needed to execute a task;
 * they never include resumes, match reasoning or cookies. All task statuses
 * surfacing through the P6 Web DTOs are P6 names; plugin and job-pool DTOs
 * keep the persistent values.
 */
public final class DeliveryModels {
    private DeliveryModels() {
    }

    // ---- Web task management ----

    /**
     * P6 create request: jobMatchId drives the task and maps the job post;
     * jobPostId is optional and only accepted when it matches the match's job
     * (legacy callers may pass jobPostId alone and resolve the latest match).
     */
    public record CreateTaskRequest(UUID jobMatchId, UUID jobPostId) {
    }

    public record TaskView(
            UUID id,
            UUID jobPostId,
            UUID jobMatchId,
            String status,
            String greeting,
            int version,
            int confirmationVersion,
            Instant confirmedAt,
            Instant createdAt
    ) {
    }

    public record UpdateGreetingRequest(int version, String greeting) {
    }

    public record GreetingResult(UUID id, String greeting, String status, boolean confirmationRequired, int version) {
    }

    public record ConfirmRequest(int version, boolean acknowledged, UUID assignedDeviceId) {
    }

    public record ConfirmResult(
            UUID id,
            String status,
            int confirmationVersion,
            Instant confirmedAt,
            UUID assignedDeviceId,
            int version
    ) {
    }

    public record SkipRequest(int version, String reason) {
    }

    public record SkipResult(UUID id, String status, Instant finishedAt, int version) {
    }

    public record SalaryRef(BigDecimal minK, BigDecimal maxK, Integer months, String text) {
    }

    public record JobRef(
            UUID id,
            String platform,
            String title,
            String companyName,
            String jobUrl,
            SalaryRef salary,
            String location
    ) {
    }

    /**
     * Match reference shown on delivery rows: score, platform recommendation
     * and the AI reasoning (summary + strengths/risks; the list truncates the
     * point lists to the first three entries).
     */
    public record MatchRef(UUID id, Integer score, String decision, String summary, List<String> strengths, List<String> risks) {
    }

    public record DeviceRef(UUID id, String deviceName) {
    }

    public record EventView(
            long id,
            String eventType,
            String fromStatus,
            String toStatus,
            String actorType,
            Instant createdAt,
            Map<String, Object> details
    ) {
    }

    public record TaskListItem(
            UUID id,
            String status,
            String greeting,
            int version,
            int confirmationVersion,
            Instant confirmedAt,
            JobRef job,
            MatchRef match,
            DeviceRef device,
            EventView lastEvent,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record TaskDetail(
            UUID id,
            UUID jobPostId,
            UUID jobMatchId,
            String status,
            String greeting,
            int version,
            int confirmationVersion,
            Instant confirmedAt,
            UUID assignedDeviceId,
            int attemptCount,
            ErrorInfo lastError,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt,
            JobRef job,
            MatchRef match,
            DeviceRef device,
            List<EventView> events
    ) {
    }

    public record ErrorInfo(String code, String message, Boolean retryable) {
    }

    /**
     * Global per-user delivery counts grouped by the canonical statuses.
     * Counts are computed server-side and are never affected by pagination;
     * the optional platform/keyword/recommendation filters match the list
     * endpoint.
     */
    public record SummaryResult(
            long waitingConfirm,
            long confirmed,
            long pulledByPlugin,
            long running,
            long success,
            long failed,
            long skipped,
            long pausedNeedUser,
            long total
    ) {
    }

    // ---- Job pool integration (real types replacing the round-4 placeholders) ----

    /** Active/latest task status shown on job list rows. */
    public record TaskStatusRef(UUID id, String status, Instant createdAt, Instant confirmedAt) {
    }

    /** Task reference shown on job detail. */
    public record TaskDetailRef(
            UUID id,
            String status,
            String greeting,
            int version,
            int confirmationVersion,
            Instant confirmedAt,
            Instant createdAt,
            Instant finishedAt
    ) {
    }

    // ---- Plugin execution ----

    public record PendingTaskItem(
            UUID id,
            int version,
            String status,
            String platform,
            String jobUrl,
            String externalJobId,
            String title,
            String companyName,
            String greeting,
            Instant confirmedAt,
            int confirmationVersion
    ) {
    }

    public record PendingTasksResult(List<PendingTaskItem> items, int pollAfterSeconds, Instant serverTime) {
    }

    public record StartRequest(int version, String executionId, String extensionVersion, String pageUrl) {
    }

    public record StartTaskPayload(String platform, String jobUrl, String greeting) {
    }

    public record StartResult(
            UUID id,
            String status,
            UUID leaseId,
            Instant leaseExpiresAt,
            int version,
            int attemptNumber,
            StartTaskPayload task
    ) {
    }

    public record SuccessRequest(
            UUID leaseId,
            String executionId,
            int version,
            Instant completedAt,
            String resultCode,
            Map<String, Object> evidence
    ) {
    }

    public record SuccessResult(UUID id, String status, Instant finishedAt, int version) {
    }

    public record FailRequest(
            UUID leaseId,
            String executionId,
            int version,
            Instant failedAt,
            String errorCode,
            String message,
            boolean retryable
    ) {
    }

    public record FailResult(
            UUID id,
            String status,
            String errorCode,
            boolean retryable,
            int attemptCount,
            Instant finishedAt,
            int version
    ) {
    }

    public record PauseRequest(
            UUID leaseId,
            String executionId,
            int version,
            Instant pausedAt,
            String reason,
            String message
    ) {
    }

    public record PauseResult(
            UUID id,
            String status,
            String pauseReason,
            boolean userActionRequired,
            boolean leaseReleased,
            int version
    ) {
    }

    /**
     * Batch pause request: pauses every RUNNING task of the authenticated
     * user + device. reason is optional and defaults to USER_REQUESTED;
     * only the canonical pause reasons plus USER_REQUESTED and
     * FAILURE_THRESHOLD are accepted.
     */
    public record BatchPauseRequest(String reason) {
    }

    /** Batch pause summary: the paused task ids plus the remaining RUNNING count. */
    public record BatchPauseResult(
            int pausedCount,
            int remainingRunningCount,
            List<UUID> pausedTaskIds
    ) {
    }
}
