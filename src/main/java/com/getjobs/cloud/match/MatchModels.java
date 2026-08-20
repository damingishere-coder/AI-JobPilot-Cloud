package com.getjobs.cloud.match;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MatchModels {
    private MatchModels() {
    }

    public record AnalyzeRequest(boolean force) {
    }

    public record BatchAnalyzeRequest(List<UUID> jobIds, boolean force) {
    }

    public record QueuedResult(
            UUID matchId,
            UUID jobId,
            String status,
            Instant queuedAt,
            boolean reusedExisting
    ) {
    }

    public record BatchResult(
            List<QueuedResult> accepted,
            List<UUID> rejected,
            List<ErrorItem> errors
    ) {
    }

    public record ErrorItem(UUID jobId, String reason) {
    }

    public record MatchView(
            UUID id,
            UUID jobId,
            UUID resumeId,
            UUID preferenceId,
            String status,
            Integer score,
            String decision,
            String summary,
            List<String> strengths,
            List<String> risks,
            String greeting,
            PriorityCompany priorityCompany,
            ModelInfo model,
            UsageInfo usage,
            ErrorInfo error,
            int attemptCount,
            Instant createdAt,
            Instant completedAt
    ) {
    }

    public record PriorityCompany(String name, String label) {
    }

    public record ModelInfo(String provider, String name, String promptVersion) {
    }

    public record UsageInfo(Integer inputTokens, Integer outputTokens, Integer durationMs) {
    }

    public record ErrorInfo(String code, String message) {
    }

    public record MatchSummary(
            UUID id,
            Integer score,
            String decision,
            String greeting,
            String status,
            Instant completedAt
    ) {
    }

    // Internal record used during match processing
    record MatchContext(
            UUID matchId,
            UUID userId,
            UUID jobPostId,
            UUID resumeId,
            UUID preferenceId,
            String jobTitle,
            String companyName,
            String jobDescription,
            String resumeText,
            short reviewThreshold,
            short priorityApplyThreshold,
            short applyThreshold,
            List<String> preferredCompanies,
            List<String> excludedCompanies
    ) {
    }
}
