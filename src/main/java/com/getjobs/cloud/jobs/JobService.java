package com.getjobs.cloud.jobs;

import com.getjobs.cloud.delivery.DeliveryModels;
import com.getjobs.cloud.delivery.DeliveryService;
import com.getjobs.cloud.match.MatchModels;
import com.getjobs.cloud.match.MatchRepository;
import com.getjobs.cloud.match.MatchRepository.MatchRecord;
import com.getjobs.cloud.match.MatchRepository.MatchSummaryRecord;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import com.getjobs.cloud.web.PageResult;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Profile("api")
public class JobService {
    private static final Set<String> PLATFORMS = Set.of("BOSS", "ZHILIAN", "LIEPIN", "JOB51");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "EXPIRED", "REMOVED");
    private static final Set<String> MATCH_DECISIONS = Set.of("APPLY", "REVIEW", "SKIP");
    private static final Set<String> MATCH_STATUSES = Set.of("PENDING", "PROCESSING", "SUCCEEDED", "FAILED");
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "lastSeenAt", "last_seen_at",
            "createdAt", "created_at",
            "salaryMinK", "salary_min_k",
            "title", "title"
    );

    private final JobRepository jobs;
    private final MatchRepository matches;
    private final DeliveryService delivery;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;

    public JobService(
            JobRepository jobs,
            MatchRepository matches,
            DeliveryService delivery,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager
    ) {
        this.jobs = jobs;
        this.matches = matches;
        this.delivery = delivery;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public PageResult<JobModels.JobSummary> list(
            UUID userId,
            int page,
            int size,
            String platform,
            String status,
            String keyword,
            Instant capturedFrom,
            Instant capturedTo,
            String sort,
            String matchDecision,
            String matchStatus,
            Integer minScore
    ) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String normalizedPlatform = controlled(platform, PLATFORMS, "platform");
        String normalizedStatus = controlled(status, STATUSES, "status");
        String normalizedMatchDecision = controlled(matchDecision, MATCH_DECISIONS, "matchDecision");
        String normalizedMatchStatus = controlled(matchStatus, MATCH_STATUSES, "matchStatus");
        Integer safeMinScore = null;
        if (minScore != null) {
            if (minScore < 0 || minScore > 100) {
                throw validation("minScore 必须在 0 到 100 之间");
            }
            safeMinScore = minScore;
        }
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        if (normalizedKeyword != null && normalizedKeyword.length() > 100) {
            throw validation("关键词不能超过 100 个字符");
        }
        if (normalizedKeyword != null) {
            normalizedKeyword = normalizedKeyword.replace("%", "").replace("_", "");
            if (normalizedKeyword.isBlank()) {
                normalizedKeyword = null;
            }
        }
        if (capturedFrom != null && capturedTo != null && capturedFrom.isAfter(capturedTo)) {
            throw validation("采集开始时间不能晚于结束时间");
        }
        JobModels.Query query = new JobModels.Query(
                safePage, safeSize, normalizedPlatform, normalizedStatus, normalizedKeyword,
                capturedFrom, capturedTo, orderBy(sort),
                normalizedMatchDecision, normalizedMatchStatus, safeMinScore
        );
        return inTenant(userId, () -> {
            long total = jobs.count(userId, query);
            List<JobModels.JobSummary> rawJobs = jobs.list(userId, query);
            if (!rawJobs.isEmpty()) {
                // Populate match summaries (latest per job)
                List<UUID> jobIds = rawJobs.stream().map(JobModels.JobSummary::id).toList();
                List<MatchSummaryRecord> matchRecords = matches.findLatestByJobIds(userId, jobIds);
                Map<UUID, MatchModels.MatchSummary> byJobId = new LinkedHashMap<>();
                for (MatchSummaryRecord r : matchRecords) {
                    byJobId.put(r.jobPostId(), new MatchModels.MatchSummary(
                            r.id(),
                            r.score() == null ? null : r.score().intValue(),
                            r.decision(),
                            r.greeting(),
                            r.status(),
                            r.completedAt()
                    ));
                }
                // Populate the active/latest delivery task per job
                Map<UUID, DeliveryModels.TaskStatusRef> tasksByJob = delivery.taskStatusByJob(userId, jobIds);
                rawJobs = rawJobs.stream()
                        .map(job -> new JobModels.JobSummary(
                                job.id(), job.platform(), job.title(), job.companyName(),
                                job.salary(), job.location(), job.status(),
                                byJobId.get(job.id()),
                                tasksByJob.get(job.id()),
                                job.lastSeenAt()
                        ))
                        .toList();
            }
            return PageResult.of(rawJobs, safePage, safeSize, total);
        });
    }

    public JobModels.JobDetail detail(UUID userId, UUID jobId) {
        return inTenant(userId, () -> {
            JobModels.JobDetail detail = jobs.find(userId, jobId).orElseThrow(() -> new ApiException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "岗位不存在"
            ));
            // Get latest match for this job
            MatchRecord matchRecord = matches.findLatestByJob(userId, jobId).orElse(null);
            MatchModels.MatchView matchView = matchRecord == null ? null :
                    new MatchModels.MatchView(
                            matchRecord.id(), matchRecord.jobPostId(),
                            matchRecord.resumeId(), matchRecord.preferenceId(),
                            matchRecord.status(),
                            matchRecord.score() == null ? null : matchRecord.score().intValue(),
                            matchRecord.decision(), matchRecord.summary(),
                            matchRecord.strengths(), matchRecord.risks(),
                            matchRecord.greeting(), null,
                            matchRecord.modelProvider() == null ? null :
                                    new MatchModels.ModelInfo(matchRecord.modelProvider(),
                                            matchRecord.modelName(), matchRecord.promptVersion()),
                            matchRecord.inputTokens() == null ? null :
                                    new MatchModels.UsageInfo(matchRecord.inputTokens(),
                                            matchRecord.outputTokens(), matchRecord.durationMs()),
                            matchRecord.errorCode() == null ? null :
                                    new MatchModels.ErrorInfo(matchRecord.errorCode(),
                                            matchRecord.errorMessage()),
                            matchRecord.attemptCount(),
                            matchRecord.createdAt(), matchRecord.completedAt()
                    );
            return new JobModels.JobDetail(
                    detail.id(), detail.platform(), detail.externalJobId(),
                    detail.title(), detail.companyName(), detail.salary(),
                    detail.location(), detail.experience(), detail.degree(),
                    detail.description(), detail.jobUrl(), detail.companyInfo(),
                    detail.skills(), detail.welfare(), detail.status(),
                    detail.capturedAt(), detail.lastSeenAt(),
                    matchView, delivery.taskDetailByJob(userId, jobId)
            );
        });
    }

    private String controlled(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw validation(field + " 参数不正确");
        }
        return normalized;
    }

    private String orderBy(String sort) {
        String value = sort == null || sort.isBlank() ? "lastSeenAt,desc" : sort.trim();
        String[] parts = value.split(",", -1);
        if (parts.length != 2 || !SORT_COLUMNS.containsKey(parts[0])) {
            throw validation("sort 参数不正确");
        }
        String direction = parts[1].toLowerCase(Locale.ROOT);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw validation("sort 方向只能是 asc 或 desc");
        }
        return SORT_COLUMNS.get(parts[0]) + " " + direction + " NULLS LAST, id ASC";
    }

    private <T> T inTenant(UUID userId, Supplier<T> work) {
        return transactions.execute(status -> tenants.execute(userId, work));
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
