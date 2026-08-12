package com.getjobs.cloud.jobs;

import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import com.getjobs.cloud.web.PageResult;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "lastSeenAt", "last_seen_at",
            "createdAt", "created_at",
            "salaryMinK", "salary_min_k",
            "title", "title"
    );

    private final JobRepository jobs;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;

    public JobService(
            JobRepository jobs,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager
    ) {
        this.jobs = jobs;
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
            String sort
    ) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String normalizedPlatform = controlled(platform, PLATFORMS, "platform");
        String normalizedStatus = controlled(status, STATUSES, "status");
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
                capturedFrom, capturedTo, orderBy(sort)
        );
        return inTenant(userId, () -> {
            long total = jobs.count(userId, query);
            return PageResult.of(jobs.list(userId, query), safePage, safeSize, total);
        });
    }

    public JobModels.JobDetail detail(UUID userId, UUID jobId) {
        return inTenant(userId, () -> jobs.find(userId, jobId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "岗位不存在"
        )));
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
