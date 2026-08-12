package com.getjobs.cloud.jobs;

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
            MatchModels.DeliveryTaskStatus deliveryTaskStatus,
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
            MatchModels.DeliveryTaskPlaceholder deliveryTask
    ) {
    }

    record Query(
            int page,
            int size,
            String platform,
            String status,
            String keyword,
            Instant capturedFrom,
            Instant capturedTo,
            String orderBy,
            String matchDecision,
            String matchStatus,
            Integer minScore
    ) {
    }
}
