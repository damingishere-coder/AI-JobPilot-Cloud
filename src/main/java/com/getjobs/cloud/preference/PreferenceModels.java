package com.getjobs.cloud.preference;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PreferenceModels {
    private PreferenceModels() {
    }

    public record UpdateRequest(
            Integer version,
            List<String> targetTitles,
            List<String> cities,
            BigDecimal salaryMinK,
            BigDecimal salaryMaxK,
            List<String> experienceLevels,
            List<String> degreeLevels,
            List<String> industries,
            List<String> companyScales,
            List<String> preferredCompanies,
            List<String> excludedCompanies,
            List<String> excludedKeywords,
            Map<String, Object> extraFilters
    ) {
    }

    public record PreferenceView(
            UUID id,
            int version,
            List<String> targetTitles,
            List<String> cities,
            BigDecimal salaryMinK,
            BigDecimal salaryMaxK,
            List<String> experienceLevels,
            List<String> degreeLevels,
            List<String> industries,
            List<String> companyScales,
            List<String> preferredCompanies,
            List<String> excludedCompanies,
            List<String> excludedKeywords,
            Map<String, Object> extraFilters,
            Instant updatedAt
    ) {
    }
}
