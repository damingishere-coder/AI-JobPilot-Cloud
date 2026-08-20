package com.getjobs.cloud.ai;

import java.util.List;

public interface AiMatchClient {

    MatchResponse analyze(MatchRequest request);

    record MatchRequest(
            String jobTitle,
            String companyName,
            String jobDescription,
            String resumeText,
            List<String> targetTitles,
            List<String> preferredCompanies,
            List<String> excludedCompanies,
            List<String> excludedKeywords
    ) {
    }

    record MatchResponse(
            int score,
            String summary,
            List<String> strengths,
            List<String> risks,
            String greeting,
            String modelProvider,
            String modelName,
            String promptVersion,
            Integer inputTokens,
            Integer outputTokens,
            Integer durationMs
    ) {
    }
}
