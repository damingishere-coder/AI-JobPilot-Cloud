package com.getjobs.cloud.match;

import com.getjobs.cloud.ai.AiMatchProperties;
import com.getjobs.cloud.jobs.JobModels;
import com.getjobs.cloud.jobs.JobRepository;
import com.getjobs.cloud.match.MatchRepository.MatchRecord;
import com.getjobs.cloud.match.MatchRepository.MatchSummaryRecord;
import com.getjobs.cloud.preference.PreferenceRepository;
import com.getjobs.cloud.preference.PreferenceRepository.PreferenceRecord;
import com.getjobs.cloud.quota.QuotaService;
import com.getjobs.cloud.resume.ResumeRepository;
import com.getjobs.cloud.resume.ResumeRecord;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiError;
import com.getjobs.cloud.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.getjobs.cloud.quota.QuotaConstants.REASON_AI_ANALYSIS_RESERVE;
import static com.getjobs.cloud.quota.QuotaConstants.REFERENCE_JOB_MATCH;
import static com.getjobs.cloud.quota.QuotaConstants.RESOURCE_AI_ANALYSIS;

@Service
@Profile("api")
public class MatchService {
    private static final Logger log = LoggerFactory.getLogger(MatchService.class);
    private static final int MAX_BATCH_SIZE = 50;
    private static final String QUOTA_BASE_KEY_PREFIX = "ai:";

    private final MatchRepository matches;
    private final MatchOutboxRepository outbox;
    private final JobRepository jobs;
    private final ResumeRepository resumes;
    private final PreferenceRepository preferences;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;
    private final AiMatchProperties aiProperties;
    private final QuotaService quotas;
    private final Clock clock;

    public MatchService(
            MatchRepository matches,
            MatchOutboxRepository outbox,
            JobRepository jobs,
            ResumeRepository resumes,
            PreferenceRepository preferences,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager,
            AiMatchProperties aiProperties,
            QuotaService quotas,
            Clock clock
    ) {
        this.matches = matches;
        this.outbox = outbox;
        this.jobs = jobs;
        this.resumes = resumes;
        this.preferences = preferences;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.aiProperties = aiProperties;
        this.quotas = quotas;
        this.clock = clock;
    }

    public MatchModels.QueuedResult analyze(UUID userId, UUID jobId, boolean force) {
        return transactions.execute(status -> tenants.execute(userId, () -> {
            // Validate job exists and belongs to user (RLS ensures ownership)
            JobModels.JobDetail job = jobs.find(userId, jobId).orElseThrow(() -> new ApiException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "岗位不存在"
            ));

            // Validate current resume exists and is parsed
            ResumeRecord resume = resumes.findCurrent(userId).orElseThrow(() -> new ApiException(
                    HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_FAILED",
                    "请先上传并解析一份简历"
            ));
            if (!"PARSED".equals(resume.parseStatus())) {
                throw new ApiException(
                        HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_FAILED",
                        "简历尚未完成文本解析，请等待解析完成后再试"
                );
            }

            // Validate current preference exists
            PreferenceRecord preference = preferences.findCurrent(userId, false).orElseThrow(() -> new ApiException(
                    HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_FAILED",
                    "请先设置求职目标"
            ));

            // Build input fingerprint (includes provider/model/promptVersion)
            String fingerprint = buildFingerprint(job, resume, preference);

            // Check existing match by fingerprint
            Optional<MatchRecord> existing = matches.findByFingerprint(userId, fingerprint);
            if (existing.isPresent()) {
                MatchRecord record = existing.get();
                if ("SUCCEEDED".equals(record.status()) || "PENDING".equals(record.status())
                        || "PROCESSING".equals(record.status())) {
                    // Idempotent replay: return existing match
                    return new MatchModels.QueuedResult(
                            record.id(), jobId, record.status(), clock.instant(), true
                    );
                }
                // FAILED with same fingerprint
                if ("FAILED".equals(record.status())) {
                    if (force) {
                        // Atomically reset FAILED to PENDING + new Outbox.
                        boolean requeued = matches.forceRequeue(userId, record.id());
                        if (requeued) {
                            // Winner: rotate to a fresh random base key and reserve quota in the
                            // same transaction. A loser/concurrent replay never reaches here because
                            // forceRequeue only flips FAILED→PENDING once.
                            String baseKey = newQuotaBaseKey();
                            if (!matches.updateQuotaReservationKey(userId, record.id(), baseKey)) {
                                throw new IllegalStateException("更新匹配额度预占键失败，已回滚强制重试");
                            }
                            quotas.reserve(userId, RESOURCE_AI_ANALYSIS, baseKey,
                                    REFERENCE_JOB_MATCH, record.id(), REASON_AI_ANALYSIS_RESERVE);
                            return new MatchModels.QueuedResult(
                                    record.id(), jobId, "PENDING", clock.instant(), false
                            );
                        }
                        return new MatchModels.QueuedResult(
                                record.id(), jobId, record.status(), clock.instant(), true
                        );
                    }
                    // force=false on FAILED: return existing FAILED
                    return new MatchModels.QueuedResult(
                            record.id(), jobId, record.status(), clock.instant(), true
                    );
                }
            }

            // No existing match with this fingerprint → create new
            UUID matchId = UUID.randomUUID();
            String baseKey = newQuotaBaseKey();
            try {
                matches.insert(matchId, userId, jobId, resume.id(), preference.id(),
                        "PENDING", fingerprint, baseKey);
            } catch (DuplicateKeyException exception) {
                // Fingerprint conflict in race: retrieve existing
                return matches.findByFingerprint(userId, fingerprint)
                        .map(r -> new MatchModels.QueuedResult(
                                r.id(), jobId, r.status(), clock.instant(), true))
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.CONFLICT, "RACE_CONDITION",
                                "并发创建匹配记录失败，请重试", true, 1, List.of()));
            }

            // Reserve quota in the same transaction: QUOTA_EXCEEDED rolls back match + outbox.
            quotas.reserve(userId, RESOURCE_AI_ANALYSIS, baseKey,
                    REFERENCE_JOB_MATCH, matchId, REASON_AI_ANALYSIS_RESERVE);

            // Insert outbox event (API only writes Match + Outbox, no Redis XADD)
            String eventKey = "match:" + matchId + ":requested:" + clock.instant().toEpochMilli();
            try {
                outbox.insert(userId, matchId, "JOB_ANALYSIS_REQUESTED", eventKey);
            } catch (DuplicateKeyException ignored) {
                // event_key unique constraint: already queued
            }

            return new MatchModels.QueuedResult(
                    matchId, jobId, "PENDING", clock.instant(), false
            );
        }));
    }

    public MatchModels.BatchResult batchAnalyze(UUID userId, List<UUID> jobIds, boolean force) {
        // Reject null or empty jobIds with validation error
        if (jobIds == null || jobIds.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "jobIds 不能为空，请至少指定一个岗位"
            );
        }

        // Deduplicate
        List<UUID> deduplicated = jobIds.stream().distinct().toList();

        if (deduplicated.size() > MAX_BATCH_SIZE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "批量分析最多支持 " + MAX_BATCH_SIZE + " 个岗位"
            );
        }

        // Validate all job IDs belong to user in one go, without leaking
        List<UUID> visibleJobIds = transactions.execute(status -> tenants.execute(userId, () ->
                jobs.findVisibleJobIds(userId, deduplicated)
        ));

        List<MatchModels.QueuedResult> accepted = new ArrayList<>();
        List<UUID> rejected = new ArrayList<>();
        List<MatchModels.ErrorItem> errors = new ArrayList<>();

        for (UUID jobId : deduplicated) {
            if (!visibleJobIds.contains(jobId)) {
                rejected.add(jobId);
                continue;
            }
            try {
                MatchModels.QueuedResult result = analyze(userId, jobId, force);
                accepted.add(result);
            } catch (ApiException exception) {
                errors.add(new MatchModels.ErrorItem(jobId, exception.code()));
            }
        }

        return new MatchModels.BatchResult(
                List.copyOf(accepted),
                List.copyOf(rejected),
                List.copyOf(errors)
        );
    }

    public MatchModels.MatchView getMatch(UUID userId, UUID jobId) {
        return transactions.execute(status -> tenants.execute(userId, () -> {
            // Get latest match for this job (ordered by created_at DESC, id DESC)
            MatchRecord record = matches.findLatestByJob(userId, jobId)
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND, "MATCH_NOT_FOUND",
                            "该岗位尚未进行 AI 分析"
                    ));
            return toView(record);
        }));
    }

    public Optional<MatchModels.MatchView> findLatestMatchForJob(UUID userId, UUID jobId) {
        return transactions.execute(status -> tenants.execute(userId, () ->
                matches.findLatestByJob(userId, jobId).map(this::toView)
        ));
    }

    public List<MatchModels.MatchSummary> getMatchSummaries(UUID userId, List<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return transactions.execute(status -> tenants.execute(userId, () -> {
            List<MatchSummaryRecord> records = matches.findLatestByJobIds(userId, jobIds);
            return records.stream()
                    .map(r -> new MatchModels.MatchSummary(
                            r.id(),
                            r.score() == null ? null : r.score().intValue(),
                            r.decision(),
                            r.greeting(),
                            r.status(),
                            r.completedAt()
                    ))
                    .toList();
        }));
    }

    /**
     * Random quota reservation base key, unique per enqueue/force-requeue and
     * never derived from user input. QuotaService appends the action suffix.
     */
    private String newQuotaBaseKey() {
        return QUOTA_BASE_KEY_PREFIX + UUID.randomUUID();
    }

    private String buildFingerprint(JobModels.JobDetail job, ResumeRecord resume, PreferenceRecord preference) {
        // Include provider/model/promptVersion for uniqueness across config changes
        String source = String.join("|",
                job.title() != null ? job.title() : "",
                job.companyName() != null ? job.companyName() : "",
                job.description() != null ? job.description() : "",
                resume.id().toString(),
                String.valueOf(resume.textVersion()),
                preference.id().toString(),
                String.valueOf(preference.version()),
                aiProperties.getProvider(),
                aiProperties.getModel(),
                aiProperties.getPromptVersion()
        );
        return sha256(source);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private MatchModels.MatchView toView(MatchRecord record) {
        return new MatchModels.MatchView(
                record.id(),
                record.jobPostId(),
                record.resumeId(),
                record.preferenceId(),
                record.status(),
                record.score() == null ? null : record.score().intValue(),
                record.decision(),
                record.summary(),
                record.strengths(),
                record.risks(),
                record.greeting(),
                null, // priorityCompany - computed by worker from preference
                record.modelProvider() == null ? null :
                        new MatchModels.ModelInfo(record.modelProvider(), record.modelName(), record.promptVersion()),
                record.inputTokens() == null ? null :
                        new MatchModels.UsageInfo(record.inputTokens(), record.outputTokens(), record.durationMs()),
                record.errorCode() == null ? null :
                        new MatchModels.ErrorInfo(record.errorCode(), record.errorMessage()),
                record.attemptCount(),
                record.createdAt(),
                record.completedAt()
        );
    }
}
