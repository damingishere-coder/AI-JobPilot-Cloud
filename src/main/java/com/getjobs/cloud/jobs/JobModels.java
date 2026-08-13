package com.getjobs.cloud.jobs;

import com.getjobs.cloud.delivery.DeliveryModels;
import com.getjobs.cloud.match.MatchModels;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JobModels {
    private JobModels() {
    }

    public record Salary(BigDecimal minK, BigDecimal maxK, Integer months, String text) {
    }

    public record JobSummary(
            UUID id,
            String platform,
            String title,
            String companyName,
            Salary salary,
            String location,
            String status,
            MatchModels.MatchSummary latestMatchSummary,
            DeliveryModels.TaskStatusRef deliveryTaskStatus,
            Instant lastSeenAt
    ) {
    }

    public record JobDetail(
            UUID id,
            String platform,
            String externalJobId,
            String title,
            String companyName,
            Salary salary,
            String location,
            String experience,
            String degree,
            String description,
            String jobUrl,
            Map<String, Object> companyInfo,
            List<String> skills,
            List<String> welfare,
            String status,
            Instant capturedAt,
            Instant lastSeenAt,
            MatchModels.MatchView latestMatch,
            DeliveryModels.TaskDetailRef deliveryTask
    ) {
    }

    /**
     * Finite sort key for the job list. The repository resolves each value to a
     * pre-declared ORDER BY constant; no free-form SQL string is ever passed
     * through this type.
     */
    enum JobSort {
        LAST_SEEN_ASC, LAST_SEEN_DESC,
        CREATED_ASC, CREATED_DESC,
        SALARY_MIN_ASC, SALARY_MIN_DESC,
        TITLE_ASC, TITLE_DESC
    }

    record Query(
            int page,
            int size,
            String platform,
            String status,
            String keyword,
            Instant capturedFrom,
            Instant capturedTo,
            JobSort sort,
            String matchDecision,
            String matchStatus,
            Integer minScore
    ) {
    }
}
