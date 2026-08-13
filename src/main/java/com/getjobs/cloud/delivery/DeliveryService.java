package com.getjobs.cloud.delivery;

import com.getjobs.cloud.audit.AuditWriter;
import com.getjobs.cloud.auth.AuditLogService;
import com.getjobs.cloud.auth.RequestMetadata;
import com.getjobs.cloud.auth.UserRole;
import com.getjobs.cloud.delivery.DeliveryRepository.EventRow;
import com.getjobs.cloud.delivery.DeliveryRepository.FinishOutcome;
import com.getjobs.cloud.delivery.DeliveryRepository.PauseOutcome;
import com.getjobs.cloud.delivery.DeliveryRepository.PendingTaskRow;
import com.getjobs.cloud.delivery.DeliveryRepository.StartOutcome;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskDetailRow;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskListRow;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskQuery;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskSort;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskRecord;
import com.getjobs.cloud.delivery.DeliveryRepository.TaskStatusRow;
import com.getjobs.cloud.jobs.JobModels;
import com.getjobs.cloud.jobs.JobRepository;
import com.getjobs.cloud.match.MatchRepository;
import com.getjobs.cloud.match.MatchRepository.MatchRecord;
import com.getjobs.cloud.plugin.PluginRepository;
import com.getjobs.cloud.plugin.PluginRepository.DeviceRecord;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiException;
import com.getjobs.cloud.web.PageResult;
import com.getjobs.cloud.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Delivery task state machine. Web operations run under the RLS tenant
 * context with optimistic version checks; plugin operations are delegated to
 * the atomic SECURITY DEFINER functions in PostgreSQL. Plugin state changes
 * and their security audit are committed in one transaction; idempotent
 * replays never write a second audit row.
 */
@Service
@Profile("api")
public class DeliveryService {
    static final Set<String> TASK_STATUSES = Set.of(
            "PENDING_CONFIRMATION", "CONFIRMED", "LEASED", "EXECUTING", "SUCCEEDED",
            "FAILED", "PAUSED", "SKIPPED", "CANCELLED"
    );
    private static final Set<String> SUPPORTED_DELIVERY_PLATFORMS = Set.of("BOSS", "ZHILIAN");
    private static final Set<String> RESULT_CODES = Set.of("DELIVERED", "ALREADY_DELIVERED");
    private static final Set<String> ERROR_CODES = Set.of("JOB_CLOSED", "BUTTON_NOT_FOUND", "NETWORK_ERROR", "UNKNOWN_ERROR");
    private static final Set<String> PAUSE_REASONS = Set.of(
            "CAPTCHA_REQUIRED", "LOGIN_REQUIRED", "RISK_CONTROL", "PAGE_CHANGED", "USER_ACTION_REQUIRED"
    );
    private static final Set<String> PAGE_STATES = Set.of("SUCCESS_NOTICE", "ALREADY_DELIVERED");
    private static final Set<String> SKIPPABLE_STATUSES = Set.of(
            "PENDING_CONFIRMATION", "CONFIRMED", "PAUSED", "FAILED"
    );
    private static final int MAX_URL_LENGTH = 2000;
    private static final int MAX_MESSAGE_CODE_POINTS = 200;

    /**
     * Credential-like markers that must never be accepted in plugin free text
     * (error/pause messages). See CLOUD_SECURITY.md: inputs that look like
     * Cookie/Authorization/Bearer/password/token=, LocalStorage or
     * SessionStorage content are rejected before any storage, so the original
     * value can never reach the database, logs or the audit trail. The literal
     * terms below are the sanctioned denylist, matched case-insensitively.
     */
    private static final List<String> SENSITIVE_MARKERS = List.of(
            "cookie", "authorization", "bearer", "password", "token=",
            "localstorage", "sessionstorage"
    );

    private final DeliveryRepository tasks;
    private final JobRepository jobs;
    private final MatchRepository matches;
    private final PluginRepository devices;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;
    private final DeliveryProperties properties;
    private final AuditWriter audit;
    private final AuditLogService auditLogs;
    private final Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public DeliveryService(
            DeliveryRepository tasks,
            JobRepository jobs,
            MatchRepository matches,
            PluginRepository devices,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager,
            DeliveryProperties properties,
            AuditWriter audit,
            AuditLogService auditLogs,
            Clock clock,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper
    ) {
        this.tasks = tasks;
        this.jobs = jobs;
        this.matches = matches;
        this.devices = devices;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.audit = audit;
        this.auditLogs = auditLogs;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ==== Web: create ====

    public DeliveryModels.TaskView createTask(
            UUID userId, UUID jobPostId, UUID jobMatchId, String idempotencyKey, String requestId
    ) {
        String keyHash = sha256(userId + ":" + idempotencyKey);
        return transactions.execute(status -> tenants.execute(userId, () -> {
            JobModels.JobDetail job = jobs.find(userId, jobPostId).orElseThrow(() -> new ApiException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "岗位不存在"
            ));
            if (!SUPPORTED_DELIVERY_PLATFORMS.contains(job.platform())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                        "该平台暂不支持创建投递任务"
                );
            }

            MatchRecord match = resolveMatch(userId, jobPostId, jobMatchId);
            if (!"SUCCEEDED".equals(match.status())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                        "该岗位的 AI 匹配尚未完成，请稍后再试"
                );
            }
            if (!"APPLY".equals(match.decision()) && !"REVIEW".equals(match.decision())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                        "该岗位的 AI 建议为跳过，无法创建投递任务"
                );
            }

            String greeting = "BOSS".equals(job.platform()) ? match.greeting() : null;
            String payloadHash = sha256(userId + "|" + jobPostId + "|" + match.id() + "|" + greeting);

            // Idempotency first: a replayed key returns the original task even when
            // the task is still active; a reused key with a different payload conflicts.
            Optional<TaskRecord> replayed = tasks.findByKeyHash(userId, keyHash);
            if (replayed.isPresent()) {
                if (payloadHash.equals(replayed.get().idempotencyPayloadHash())) {
                    return toView(replayed.get());
                }
                throw new ApiException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key 已用于其他请求"
                );
            }

            if (tasks.findActiveForJob(userId, jobPostId).isPresent()) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "DUPLICATE_ACTIVE_TASK",
                        "该岗位已存在进行中的投递任务"
                );
            }

            UUID taskId = UUID.randomUUID();
            Optional<UUID> inserted = tasks.insertTask(
                    taskId, userId, jobPostId, match.id(), "PENDING_CONFIRMATION",
                    greeting, keyHash, payloadHash
            );
            if (inserted.isEmpty()) {
                // Lost a race: either the idempotency key or the active-task index.
                TaskRecord previous = tasks.findByKeyHash(userId, keyHash).orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT, "DUPLICATE_ACTIVE_TASK",
                        "该岗位已存在进行中的投递任务"
                ));
                if (payloadHash.equals(previous.idempotencyPayloadHash())) {
                    return toView(previous);
                }
                throw new ApiException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key 已用于其他请求"
                );
            }

            tasks.insertEvent(userId, taskId, "CREATED", null, "PENDING_CONFIRMATION",
                    "USER", userId, requestId, "created:" + taskId, null,
                    Map.of("source", "WEB"));
            return toView(tasks.findById(userId, taskId).orElseThrow());
        }));
    }

    private MatchRecord resolveMatch(UUID userId, UUID jobPostId, UUID jobMatchId) {
        MatchRecord match;
        if (jobMatchId != null) {
            match = matches.findById(userId, jobMatchId).orElseThrow(() -> new ApiException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "匹配记录不存在"
            ));
            if (!jobPostId.equals(match.jobPostId())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                        "匹配记录与岗位不一致"
                );
            }
        } else {
            match = matches.findLatestByJob(userId, jobPostId).orElseThrow(() -> new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                    "请先完成该岗位的 AI 分析"
            ));
        }
        return match;
    }

    // ==== Web: list / detail ====

    public PageResult<DeliveryModels.TaskListItem> list(
            UUID userId, int page, int size, List<String> statuses, String platform,
            String keyword, Instant createdFrom, Instant createdTo, String sort
    ) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        List<String> normalizedStatuses = new ArrayList<>();
        if (statuses != null) {
            for (String status : statuses) {
                String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
                if (!TASK_STATUSES.contains(value)) {
                    throw validation("status 参数不正确");
                }
                if (!normalizedStatuses.contains(value)) {
                    normalizedStatuses.add(value);
                }
            }
        }
        String normalizedPlatform = controlled(platform, SUPPORTED_DELIVERY_PLATFORMS, "platform");
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        if (normalizedKeyword != null) {
            normalizedKeyword = normalizedKeyword.replace("%", "").replace("_", "");
            if (normalizedKeyword.length() > 100) {
                throw validation("关键词不能超过 100 个字符");
            }
            if (normalizedKeyword.isBlank()) {
                normalizedKeyword = null;
            }
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw validation("创建开始时间不能晚于结束时间");
        }
        TaskQuery query = new TaskQuery(
                safePage, safeSize, normalizedStatuses, normalizedPlatform, normalizedKeyword,
                createdFrom, createdTo, parseSort(sort)
        );
        return inTenant(userId, () -> {
            long total = tasks.count(userId, query);
            List<DeliveryModels.TaskListItem> items = tasks.list(userId, query).stream()
                    .map(this::toListItem)
                    .toList();
            return PageResult.of(items, safePage, safeSize, total);
        });
    }

    public DeliveryModels.TaskDetail detail(UUID userId, UUID taskId) {
        return inTenant(userId, () -> {
            TaskRecord task = tasks.findById(userId, taskId).orElseThrow(() -> new ApiException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "投递任务不存在"
            ));
            JobModels.JobDetail job = jobs.find(userId, task.jobPostId()).orElse(null);
            MatchRecord match = task.jobMatchId() == null ? null
                    : matches.findById(userId, task.jobMatchId()).orElse(null);
            DeviceRecord device = task.assignedDeviceId() == null ? null
                    : devices.findDevice(userId, task.assignedDeviceId()).orElse(null);
            List<DeliveryModels.EventView> events = tasks.listEvents(userId, taskId).stream()
                    .map(this::toEventView)
                    .toList();
            return new DeliveryModels.TaskDetail(
                    task.id(), task.jobPostId(), task.jobMatchId(), task.status(), task.greeting(),
                    task.version(), task.confirmationVersion(), task.confirmedAt(),
                    task.assignedDeviceId(), task.attemptCount(),
                    task.lastErrorCode() == null ? null :
                            new DeliveryModels.ErrorInfo(task.lastErrorCode(), task.lastErrorMessage(),
                                    task.lastErrorRetryable()),
                    task.startedAt(), task.finishedAt(), task.createdAt(), task.updatedAt(),
                    job == null ? null : new DeliveryModels.JobRef(
                            job.id(), job.platform(), job.title(), job.companyName(), job.jobUrl()),
                    match == null ? null : new DeliveryModels.MatchRef(
                            match.id(), match.score() == null ? null : match.score().intValue(),
                            match.decision()),
                    device == null ? null : new DeliveryModels.DeviceRef(device.id(), device.deviceName()),
                    events
            );
        });
    }

    // ==== Web: greeting ====

    public DeliveryModels.GreetingResult updateGreeting(UUID userId, UUID taskId, int version, String greeting) {
        String normalized = greeting == null ? null : greeting.trim();
        if (normalized != null && normalized.codePointCount(0, normalized.length()) > properties.getGreetingMaxCodePoints()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "GREETING_TOO_LONG",
                    "招呼语不能超过 " + properties.getGreetingMaxCodePoints() + " 个字符"
            );
        }
        return inTenant(userId, () -> {
            TaskRecord task = requireTask(userId, taskId);
            requireVersion(task, version);
            String platform = jobs.find(userId, task.jobPostId())
                    .map(JobModels.JobDetail::platform)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "投递任务不存在"));
            if (!"BOSS".equals(platform)) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "GREETING_UNSUPPORTED",
                        "该平台暂不支持自定义招呼语"
                );
            }
            if (!greetingEditable(task)) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                        "当前状态不允许修改招呼语"
                );
            }
            // Any edit outside PENDING_CONFIRMATION changes the content that was
            // confirmed: the confirmation is invalidated and the user must
            // confirm again.
            boolean invalidates = !"PENDING_CONFIRMATION".equals(task.status());
            boolean updated = tasks.updateGreeting(userId, taskId, version, normalized);
            if (!updated) {
                throw conflictAfterRead(userId, taskId, version);
            }
            String newStatus = invalidates ? "PENDING_CONFIRMATION" : task.status();
            tasks.insertEvent(userId, taskId, "GREETING_UPDATED", task.status(), newStatus,
                    "USER", userId, null, "greeting:" + UUID.randomUUID(), null,
                    Map.of("invalidatesConfirmation", invalidates));
            if (invalidates) {
                tasks.insertEvent(userId, taskId, "CONFIRMATION_INVALIDATED", task.status(), "PENDING_CONFIRMATION",
                        "USER", userId, null, "confirmation-invalidated:" + UUID.randomUUID(), null,
                        Map.of("reason", "GREETING_CHANGED"));
            }
            TaskRecord after = tasks.findById(userId, taskId).orElseThrow();
            return new DeliveryModels.GreetingResult(
                    after.id(), after.greeting(), after.status(),
                    "PENDING_CONFIRMATION".equals(after.status()), after.version()
            );
        });
    }

    /**
     * Greeting edits are allowed while unconfirmed, on confirmed/paused tasks
     * and on retryable failures. A non-retryable terminal failure (e.g.
     * JOB_CLOSED) cannot be reopened by editing content.
     */
    private static boolean greetingEditable(TaskRecord task) {
        return "PENDING_CONFIRMATION".equals(task.status())
                || "CONFIRMED".equals(task.status())
                || "PAUSED".equals(task.status())
                || ("FAILED".equals(task.status()) && task.lastErrorRetryable());
    }

    // ==== Web: confirm ====

    public DeliveryModels.ConfirmResult confirm(
            UUID userId, UUID taskId, int version, boolean acknowledged,
            UUID assignedDeviceId, String idempotencyKey, String requestId,
            RequestMetadata request, UserRole role
    ) {
        if (!acknowledged) {
            throw validation("必须明确确认后才能投递");
        }
        String keyHash = sha256(idempotencyKey);
        String eventKey = "confirm:" + keyHash;
        String payloadHash = sha256(userId + "|" + taskId + "|" + version + "|" + acknowledged + "|" + assignedDeviceId);
        return inTenant(userId, () -> {
            // The task row lock serializes concurrent identical confirms: the
            // loser re-reads the committed state, finds the CONFIRMED event and
            // replays without writing state, an event or an audit row.
            TaskRecord task = tasks.findByIdForUpdate(userId, taskId).orElseThrow(() -> new ApiException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "投递任务不存在"
            ));

            EventRow replay = tasks.findEvent(userId, taskId, eventKey).orElse(null);
            if (replay != null) {
                String previousHash = String.valueOf(replay.details().get("payloadHash"));
                if (!payloadHash.equals(previousHash)) {
                    throw idempotencyConflict();
                }
                return confirmResult(task);
            }
            // The same key must never mint a second event of any kind
            // (e.g. confirm vs skip): stable 409 instead of a unique-index 500.
            if (tasks.findEventByKeyHash(userId, taskId, keyHash).isPresent()) {
                throw idempotencyConflict();
            }

            requireVersion(task, version);
            if (!"PENDING_CONFIRMATION".equals(task.status())
                    && !"PAUSED".equals(task.status())
                    && !("FAILED".equals(task.status()) && task.lastErrorRetryable())) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                        "当前状态不允许确认"
                );
            }
            // A null device is a valid confirm: the task stays CONFIRMED and any
            // capable device of this user may claim it, the winner binds at start.
            validateAssignedDevice(userId, task.jobPostId(), assignedDeviceId);

            boolean updated = tasks.confirmTask(userId, taskId, version, assignedDeviceId);
            if (!updated) {
                throw conflictAfterRead(userId, taskId, version);
            }
            // LinkedHashMap (not Map.of) because assignedDeviceId may be null.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("payloadHash", payloadHash);
            details.put("confirmationVersion", task.confirmationVersion() + 1);
            details.put("assignedDeviceId", assignedDeviceId == null ? null : assignedDeviceId.toString());
            tasks.insertEvent(userId, taskId, "CONFIRMED", task.status(), "CONFIRMED",
                    "USER", userId, requestId, eventKey, keyHash, details);
            // Web audit inside the same transaction as the real transition so a
            // replay can never duplicate it; fields stay within the stable
            // whitelist (no greeting/URL/idempotency plaintext).
            auditLogs.append(userId, role, "DELIVERY_TASK_CONFIRMED",
                    "DELIVERY_TASK", taskId, "SUCCESS", request,
                    Map.of("confirmationVersion", task.confirmationVersion() + 1,
                            "assignedDeviceId", assignedDeviceId == null ? "" : assignedDeviceId.toString()));
            return confirmResult(tasks.findById(userId, taskId).orElseThrow());
        });
    }

    private ApiException idempotencyConflict() {
        return new ApiException(
                HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "Idempotency-Key 已用于其他请求"
        );
    }

    private void validateAssignedDevice(UUID userId, UUID jobPostId, UUID assignedDeviceId) {
        if (assignedDeviceId == null) {
            return;
        }
        DeviceRecord device = devices.findActiveDevice(userId, assignedDeviceId).orElseThrow(() -> new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "DEVICE_NOT_AVAILABLE", "指定设备不可用，请重新选择"
        ));
        String platform = jobs.find(userId, jobPostId)
                .map(JobModels.JobDetail::platform)
                .orElse("UNKNOWN");
        if (!device.capabilities().contains(platform)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "DEVICE_NOT_AVAILABLE",
                    "指定设备不支持该岗位的招聘平台"
            );
        }
    }

    private DeliveryModels.ConfirmResult confirmResult(TaskRecord task) {
        return new DeliveryModels.ConfirmResult(
                task.id(), task.status(), task.confirmationVersion(),
                task.confirmedAt(), task.assignedDeviceId(), task.version()
        );
    }

    // ==== Web: skip ====

    public DeliveryModels.SkipResult skip(
            UUID userId, UUID taskId, int version, String reason, String idempotencyKey, String requestId,
            RequestMetadata request, UserRole role
    ) {
        if (reason != null && reason.trim().length() > 200) {
            throw validation("reason 不能超过 200 个字符");
        }
        String safeReason = reason == null ? null : reason.trim();
        String keyHash = sha256(idempotencyKey);
        String eventKey = "skip:" + keyHash;
        String payloadHash = sha256(userId + "|" + taskId + "|" + version + "|" + safeReason);
        return inTenant(userId, () -> {
            // Same row-lock pattern as confirm: concurrent identical skips
            // serialize and the loser replays instead of double-writing.
            TaskRecord task = tasks.findByIdForUpdate(userId, taskId).orElseThrow(() -> new ApiException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "投递任务不存在"
            ));

            EventRow replay = tasks.findEvent(userId, taskId, eventKey).orElse(null);
            if (replay != null) {
                String previousHash = String.valueOf(replay.details().get("payloadHash"));
                if (!payloadHash.equals(previousHash)) {
                    throw idempotencyConflict();
                }
                return new DeliveryModels.SkipResult(
                        task.id(), task.status(), task.finishedAt(), task.version()
                );
            }
            if (tasks.findEventByKeyHash(userId, taskId, keyHash).isPresent()) {
                throw idempotencyConflict();
            }

            requireVersion(task, version);
            if (!SKIPPABLE_STATUSES.contains(task.status())) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                        "当前状态不允许跳过，已执行或已结束的任务不能跳过"
                );
            }
            boolean updated = tasks.skipTask(userId, taskId, version);
            if (!updated) {
                throw conflictAfterRead(userId, taskId, version);
            }
            tasks.insertEvent(userId, taskId, "SKIPPED", task.status(), "SKIPPED",
                    "USER", userId, requestId, eventKey, keyHash,
                    Map.of("payloadHash", payloadHash, "reason", safeReason == null ? "" : safeReason));
            auditLogs.append(userId, role, "DELIVERY_TASK_SKIPPED",
                    "DELIVERY_TASK", taskId, "SUCCESS", request, Map.of());
            TaskRecord after = tasks.findById(userId, taskId).orElseThrow();
            return new DeliveryModels.SkipResult(after.id(), after.status(), after.finishedAt(), after.version());
        });
    }

    // ==== Plugin: pending ====

    public DeliveryModels.PendingTasksResult pending(
            UUID userId, UUID deviceId, List<String> capabilities, Integer limit, String platform
    ) {
        int safeLimit = limit == null ? properties.getPendingDefaultLimit()
                : Math.max(1, Math.min(limit, properties.getPendingMaxLimit()));
        String normalizedPlatform = controlled(platform, SUPPORTED_DELIVERY_PLATFORMS, "platform");
        List<PendingTaskRow> rows = inTenant(userId, () ->
                tasks.findPendingForDevice(userId, deviceId, capabilities, normalizedPlatform, safeLimit)
        );
        List<DeliveryModels.PendingTaskItem> items = rows.stream()
                // The plugin navigates the stored job URL, so untrusted rows are
                // never handed out for execution. The invalid URL itself is not
                // logged anywhere.
                .map(r -> new DeliveryModels.PendingTaskItem(
                        r.id(), r.version(), r.platform(),
                        normalizeTrustedJobUrl(r.jobUrl(), r.platform()).orElse(null),
                        r.externalJobId(),
                        r.title(), r.companyName(), r.greeting(), r.confirmedAt(),
                        r.confirmationVersion()
                ))
                .filter(item -> item.jobUrl() != null)
                .toList();
        return new DeliveryModels.PendingTasksResult(
                items, properties.getPollAfterSeconds(), clock.instant()
        );
    }

    // ==== Plugin: start ====

    public DeliveryModels.StartResult start(
            UUID userId, UUID deviceId, UUID taskId, DeliveryModels.StartRequest request,
            String idempotencyKey
    ) {
        validateExecutionId(request.executionId());
        if (!isNumericExtensionVersion(request.extensionVersion())) {
            throw validation("extensionVersion 必须为纯数字版本格式，如 1.2.0");
        }

        // Pre-read the task/job to validate both the optional client pageUrl and
        // the stored job URL before any state change: an untrusted URL is never
        // handed out for execution.
        TaskRecord task = inTenant(userId, () -> requireTask(userId, taskId));
        JobModels.JobDetail job = inTenant(userId, () -> jobs.find(userId, task.jobPostId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "投递任务不存在")));
        validatePageUrl(request.pageUrl(), job.platform());
        normalizeTrustedJobUrl(job.jobUrl(), job.platform())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "UNTRUSTED_JOB_URL", "岗位链接不受信任，无法执行"
                ));

        String keyHash = sha256(idempotencyKey);
        String payloadHash = sha256(userId + "|" + deviceId + "|" + taskId + "|" + idempotencyKey + "|"
                + request.version() + "|" + request.executionId() + "|" + request.extensionVersion()
                + "|" + request.pageUrl());

        return transactions.execute(status -> tenants.execute(userId, () -> {
            StartOutcome outcome = tasks.pluginStart(
                    userId, deviceId, taskId, request.version(), request.executionId(),
                    keyHash, payloadHash, properties.getLeaseSeconds(), properties.getMaxAttempts()
            );
            if (!outcome.ok() && !outcome.replay()) {
                throw mapStartFailure(outcome);
            }
            // The function re-reads the job row; normalize again defensively so a
            // concurrently edited URL can never reach the plugin with tracking or
            // redirect parameters. A failure here rolls the start back.
            String responseUrl = normalizeTrustedJobUrl(outcome.jobUrl(), job.platform())
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "UNTRUSTED_JOB_URL", "岗位链接不受信任，无法执行"
                    ));
            // Audit only the real transition, in the same transaction; replays
            // must not write a second audit row.
            if (outcome.ok()) {
                pluginAudit(userId, deviceId, "PLUGIN_TASK_STARTED", taskId,
                        Map.of("attemptNumber", outcome.attemptNumber()));
            }
            return new DeliveryModels.StartResult(
                    taskId, outcome.taskStatus(), outcome.leaseId(), outcome.leaseExpiresAt(),
                    outcome.newVersion(), outcome.attemptNumber(),
                    new DeliveryModels.StartTaskPayload(outcome.platform(), responseUrl, outcome.greeting())
            );
        }));
    }

    private ApiException mapStartFailure(StartOutcome outcome) {
        return switch (outcome.outcome()) {
            case "NOT_FOUND" -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "投递任务不存在");
            case "IDEMPOTENCY_CONFLICT" -> new ApiException(
                    HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency-Key 已用于其他请求"
            );
            case "VERSION_CONFLICT" -> new ApiException(
                    HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT", "任务状态已变化，请重新获取任务"
            );
            case "TASK_ALREADY_CLAIMED" -> new ApiException(
                    HttpStatus.CONFLICT, "TASK_ALREADY_CLAIMED", "任务已被其他设备领取"
            );
            case "MAX_ATTEMPTS" -> new ApiException(
                    HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "任务已达到最大尝试次数"
            );
            case "DEVICE_UNAVAILABLE" -> new ApiException(
                    HttpStatus.FORBIDDEN, "DEVICE_REVOKED", "设备当前不可用或平台能力不匹配"
            );
            default -> new ApiException(
                    HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "当前状态不允许领取任务"
            );
        };
    }

    // ==== Plugin: success / fail / pause ====

    public DeliveryModels.SuccessResult success(
            UUID userId, UUID deviceId, UUID taskId, DeliveryModels.SuccessRequest request,
            String idempotencyKey
    ) {
        validateExecutionId(request.executionId());
        String resultCode = request.resultCode() == null ? null : request.resultCode().toUpperCase(Locale.ROOT);
        if (!RESULT_CODES.contains(resultCode)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_RESULT_CODE",
                    "resultCode 只允许 DELIVERED 或 ALREADY_DELIVERED"
            );
        }
        Map<String, Object> evidence = validateEvidence(request.evidence());
        String evidenceJson;
        try {
            evidenceJson = objectMapper.writeValueAsString(evidence);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw validation("evidence 内容不合法");
        }
        String keyHash = sha256(idempotencyKey);
        String payloadHash = sha256(userId + "|" + deviceId + "|" + taskId + "|" + idempotencyKey + "|"
                + request.leaseId() + "|" + request.executionId() + "|" + request.version()
                + "|" + resultCode + "|" + evidenceJson);

        return transactions.execute(status -> tenants.execute(userId, () -> {
            FinishOutcome outcome = tasks.pluginSuccess(
                    userId, deviceId, taskId, request.leaseId(), request.executionId(),
                    request.version(), request.completedAt(), resultCode, evidence,
                    keyHash, payloadHash
            );
            if (!outcome.ok() && !outcome.replay()) {
                throwFinishFailure(outcome);
            }
            TaskRecord after = requireTask(userId, taskId);
            if (outcome.ok()) {
                pluginAudit(userId, deviceId, "PLUGIN_TASK_SUCCEEDED", taskId,
                        Map.of("resultCode", resultCode));
            }
            return new DeliveryModels.SuccessResult(taskId, after.status(), after.finishedAt(), after.version());
        }));
    }

    public DeliveryModels.FailResult fail(
            UUID userId, UUID deviceId, UUID taskId, DeliveryModels.FailRequest request,
            String idempotencyKey
    ) {
        validateExecutionId(request.executionId());
        String errorCode = request.errorCode() == null ? null : request.errorCode().toUpperCase(Locale.ROOT);
        if (!ERROR_CODES.contains(errorCode)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_ERROR_CODE",
                    "errorCode 不在允许的错误码范围内"
            );
        }
        String message = sanitizeMessage(request.message(), "message");
        // Retryability is a server-side rule per error code; the client value is
        // never trusted (an unknown error stays re-confirmable, a closed job is
        // terminal).
        boolean retryable = !"JOB_CLOSED".equals(errorCode);
        String keyHash = sha256(idempotencyKey);
        String payloadHash = sha256(userId + "|" + deviceId + "|" + taskId + "|" + idempotencyKey + "|"
                + request.leaseId() + "|" + request.executionId() + "|" + request.version()
                + "|" + errorCode + "|" + message + "|" + retryable);

        return transactions.execute(status -> tenants.execute(userId, () -> {
            FinishOutcome outcome = tasks.pluginFail(
                    userId, deviceId, taskId, request.leaseId(), request.executionId(),
                    request.version(), request.failedAt(), errorCode, message, retryable,
                    keyHash, payloadHash
            );
            if (!outcome.ok() && !outcome.replay()) {
                throwFinishFailure(outcome);
            }
            TaskRecord after = requireTask(userId, taskId);
            if (outcome.ok()) {
                pluginAudit(userId, deviceId, "PLUGIN_TASK_FAILED", taskId,
                        Map.of("errorCode", errorCode, "retryable", retryable));
            }
            return new DeliveryModels.FailResult(
                    taskId, after.status(), after.lastErrorCode(), after.lastErrorRetryable(),
                    after.attemptCount(), after.finishedAt(), after.version()
            );
        }));
    }

    public DeliveryModels.PauseResult pause(
            UUID userId, UUID deviceId, UUID taskId, DeliveryModels.PauseRequest request,
            String idempotencyKey
    ) {
        validateExecutionId(request.executionId());
        String reason = request.reason() == null ? null : request.reason().toUpperCase(Locale.ROOT);
        if (!PAUSE_REASONS.contains(reason)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_PAUSE_REASON",
                    "reason 不在允许的暂停原因范围内"
            );
        }
        String message = sanitizeMessage(request.message(), "message");
        String keyHash = sha256(idempotencyKey);
        String payloadHash = sha256(userId + "|" + deviceId + "|" + taskId + "|" + idempotencyKey + "|"
                + request.leaseId() + "|" + request.executionId() + "|" + request.version()
                + "|" + reason + "|" + message);

        return transactions.execute(status -> tenants.execute(userId, () -> {
            PauseOutcome outcome = tasks.pluginPause(
                    userId, deviceId, taskId, request.leaseId(), request.executionId(),
                    request.version(), reason, message, keyHash, payloadHash
            );
            if (!outcome.ok() && !outcome.replay()) {
                throwFinishFailure(new FinishOutcome(outcome.outcome(), outcome.newVersion(), null, null));
            }
            TaskRecord after = requireTask(userId, taskId);
            if (outcome.ok()) {
                pluginAudit(userId, deviceId, "PLUGIN_TASK_PAUSED", taskId,
                        Map.of("pauseReason", reason));
            }
            return new DeliveryModels.PauseResult(
                    taskId, after.status(), after.lastErrorCode(), true, true, after.version()
            );
        }));
    }

    private void throwFinishFailure(FinishOutcome outcome) {
        ApiException exception = switch (outcome.outcome()) {
            case "OK", "REPLAY" -> null;
            case "NOT_FOUND" -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "投递任务不存在");
            case "IDEMPOTENCY_CONFLICT" -> new ApiException(
                    HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency-Key 已用于其他请求"
            );
            case "VERSION_CONFLICT" -> new ApiException(
                    HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT", "任务状态已变化，请重新获取任务"
            );
            case "LEASE_INVALID" -> new ApiException(
                    HttpStatus.CONFLICT, "LEASE_INVALID", "租约与当前执行不匹配"
            );
            case "LEASE_EXPIRED" -> new ApiException(
                    HttpStatus.CONFLICT, "LEASE_EXPIRED", "执行租约已过期"
            );
            default -> new ApiException(
                    HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "当前状态不允许该操作"
            );
        };
        if (exception != null) {
            throw exception;
        }
    }

    /** Lease expiry sweep entry point; failures propagate to the sweeper. */
    public int recoverExpiredLeases() {
        return tasks.recoverExpiredLeases(properties.getMaxAttempts());
    }

    private void pluginAudit(
            UUID userId, UUID deviceId, String action, UUID taskId, Map<String, Object> details
    ) {
        audit.append(
                userId, "PLUGIN", deviceId, action, "DELIVERY_TASK", taskId, "SUCCESS",
                MDC.get(RequestIdFilter.MDC_KEY), null, "Plugin", details
        );
    }

    // ==== validation helpers ====

    private void validateExecutionId(String executionId) {
        if (executionId == null || executionId.length() < 8 || executionId.length() > 80
                || !executionId.matches("^[A-Za-z0-9_-]+$")) {
            throw validation("executionId 必须为 8-80 位随机字符串");
        }
    }

    private static boolean isNumericExtensionVersion(String version) {
        return version != null && version.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,3}");
    }

    /**
     * Strict evidence whitelist for this delivery round: only a page state from
     * the fixed enum and an optional alreadyDelivered boolean. Screenshots,
     * HTML, DOM dumps, page text, nested objects, arrays, URLs or unknown keys
     * are rejected so they can never be stored.
     */
    private Map<String, Object> validateEvidence(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            throw validation("必须提供投递成功证据 evidence");
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : evidence.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key == null ? "" : key) {
                case "pageState" -> {
                    if (!(value instanceof String text)) {
                        throw validation("evidence.pageState 必须是字符串");
                    }
                    String state = text.toUpperCase(Locale.ROOT);
                    if (!PAGE_STATES.contains(state)) {
                        throw validation("evidence.pageState 只允许 SUCCESS_NOTICE 或 ALREADY_DELIVERED");
                    }
                    validated.put("pageState", state);
                }
                case "alreadyDelivered" -> {
                    if (!(value instanceof Boolean)) {
                        throw validation("evidence.alreadyDelivered 必须是布尔值");
                    }
                    validated.put("alreadyDelivered", value);
                }
                default -> throw validation("evidence 字段不在白名单内");
            }
        }
        if (!validated.containsKey("pageState")) {
            throw validation("evidence 必须包含 pageState");
        }
        return validated;
    }

    /**
     * Single-line, bounded, credential-free summaries only. Anything that looks
     * like Cookie/Authorization/Bearer/password/token=, LocalStorage or
     * SessionStorage content, control characters or a URL query is rejected
     * outright; the original value is never echoed, logged or stored.
     */
    private String sanitizeMessage(String value, String field) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.codePointCount(0, text.length()) > MAX_MESSAGE_CODE_POINTS) {
            throw validation(field + " 不能超过 " + MAX_MESSAGE_CODE_POINTS + " 个字符");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw validation(field + " 不能包含控制字符");
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_MARKERS) {
            if (lower.contains(marker)) {
                throw validation(field + " 包含不允许的内容");
            }
        }
        if (text.matches(".*\\?[^\\s]*=.*")) {
            throw validation(field + " 包含不允许的内容");
        }
        return text;
    }

    /**
     * Client-supplied pageUrl: HTTPS job-detail pages only, with no query,
     * fragment, port, userinfo, scripts or redirect parameters.
     */
    static void validatePageUrl(String url, String platform) {
        if (url == null) {
            return;
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "pageUrl 过长");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNTRUSTED_JOB_URL", "pageUrl 不是合法链接");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNTRUSTED_JOB_URL", "pageUrl 必须使用 HTTPS");
        }
        if (uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getQuery() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNTRUSTED_JOB_URL", "pageUrl 包含不允许的内容");
        }
        if (normalizeTrustedJobUrl(url, platform).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNTRUSTED_JOB_URL", "pageUrl 不是受信任的岗位详情链接");
        }
    }

    /**
     * Validates a stored job URL against the platform rules and returns the
     * normalized origin + path (tracking query and fragment stripped). Empty
     * means untrusted; the URL itself is never logged. Client pageUrl and the
     * stored jobUrl share this exact host/path judgment; pageUrl keeps its
     * stricter query/fragment rejection on top (see {@link #validatePageUrl}).
     */
    static Optional<String> normalizeTrustedJobUrl(String url, String platform) {
        if (url == null || url.length() > MAX_URL_LENGTH) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(scheme) || host == null || path == null || path.isBlank()) {
            return Optional.empty();
        }
        if (uri.getPort() != -1 || uri.getUserInfo() != null) {
            return Optional.empty();
        }
        // Path-normalization smuggling: any percent-encoding, raw backslashes or
        // dot/empty segments are rejected before any allowlist matching.
        if (!isSafeJobPath(url, path)) {
            return Optional.empty();
        }
        String normalizedPlatform = platform == null ? "" : platform.toUpperCase(Locale.ROOT);
        boolean jobDetail = switch (normalizedPlatform) {
            case "BOSS" -> isHostOrSubdomain(host, "zhipin.com") && bossJobDetailPath(path);
            case "ZHILIAN" -> isHostOrSubdomain(host, "zhaopin.com") && zhilianJobDetailPath(path, host);
            default -> false;
        };
        if (!jobDetail) {
            return Optional.empty();
        }
        return Optional.of("https://" + host.toLowerCase(Locale.ROOT) + path);
    }

    /**
     * Rejects path-normalization smuggling before any allowlist matching:
     * percent-encoded sequences (Java decodes them inside {@link URI#getPath()},
     * so %2F/%5C/%2e would forge slashes, backslashes or dot segments), raw
     * backslashes and dot or empty path segments.
     */
    private static boolean isSafeJobPath(String rawUrl, String path) {
        if (rawUrl.indexOf('%') >= 0 || rawUrl.indexOf('\\') >= 0) {
            return false;
        }
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i == 0 && segment.isEmpty()) {
                continue;
            }
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * BOSS job detail pages: /job_detail/&lt;id&gt; and /web/geek/job_detail/&lt;id&gt;
     * only, with exactly one non-empty ID segment. Search (/web/geek/job),
     * home, /zhaopin/ pages and deeper pseudo paths never match.
     */
    private static boolean bossJobDetailPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String id = null;
        if (lower.startsWith("/web/geek/job_detail/")) {
            id = path.substring("/web/geek/job_detail/".length());
        } else if (lower.startsWith("/job_detail/")) {
            id = path.substring("/job_detail/".length());
        }
        return id != null && isPlausibleIdSegment(id);
    }

    /**
     * ZHILIAN detail pages: a known detail segment (jobdetail / positiondetail /
     * job_detail) followed by a real ID segment, a /job/&lt;id&gt; path, or a
     * single-file job page on jobs.zhaopin.com. Home, search and list pages
     * never match, no matter the host.
     */
    private static boolean zhilianJobDetailPath(String path, String host) {
        String[] rawSegments = path.toLowerCase(Locale.ROOT).split("/");
        int start = rawSegments.length > 0 && rawSegments[0].isEmpty() ? 1 : 0;
        int count = rawSegments.length - start;
        for (int i = start; i < rawSegments.length - 1; i++) {
            String segment = rawSegments[i];
            if (i == rawSegments.length - 2
                    && ("jobdetail".equals(segment)
                    || "positiondetail".equals(segment)
                    || "job_detail".equals(segment))
                    && isPlausibleIdSegment(rawSegments[i + 1])) {
                return true;
            }
        }
        if (count == 2 && "job".equals(rawSegments[start]) && isPlausibleIdSegment(rawSegments[start + 1])) {
            return true;
        }
        // Historical jobs.zhaopin.com detail URLs are single job-file pages;
        // the host alone never whitelists home or search paths.
        if (isHostOrSubdomain(host, "jobs.zhaopin.com") && count == 1) {
            String name = rawSegments[start];
            return (name.endsWith(".htm") || name.endsWith(".html"))
                    && !NON_JOB_FILE_NAMES.contains(name);
        }
        return false;
    }

    /** An ID segment is non-empty, single-level and not a known non-detail page. */
    private static boolean isPlausibleIdSegment(String segment) {
        return !segment.isEmpty()
                && segment.indexOf('/') < 0
                && !NON_JOB_ID_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT));
    }

    private static final Set<String> NON_JOB_ID_SEGMENTS = Set.of(
            "search", "list", "home", "index", "sou", "default", "jobs"
    );

    private static final Set<String> NON_JOB_FILE_NAMES = Set.of(
            "index.htm", "index.html", "home.htm", "home.html",
            "search.htm", "search.html", "default.htm", "default.html"
    );

    /** Strict label-boundary subdomain check: evilzhipin.com is not trusted. */
    private static boolean isHostOrSubdomain(String host, String domain) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals(domain) || lower.endsWith("." + domain);
    }

    // ==== shared helpers ====

    private TaskRecord requireTask(UUID userId, UUID taskId) {
        return tasks.findById(userId, taskId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "投递任务不存在"
        ));
    }

    private void requireVersion(TaskRecord task, int version) {
        if (task.version() != version) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT",
                    "任务状态已变化，请刷新后重试"
            );
        }
    }

    private ApiException conflictAfterRead(UUID userId, UUID taskId, int expectedVersion) {
        TaskRecord current = tasks.findById(userId, taskId).orElse(null);
        if (current == null) {
            return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "投递任务不存在");
        }
        if (current.version() != expectedVersion) {
            return new ApiException(
                    HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT", "任务状态已变化，请刷新后重试"
            );
        }
        return new ApiException(
                HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "当前状态不允许该操作"
        );
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

    /**
     * Parses the client sort value into the finite
     * {@link DeliveryRepository.TaskSort} enum. The repository resolves the
     * enum to pre-declared ORDER BY constants, so a client value can never
     * reach SQL text.
     */
    static DeliveryRepository.TaskSort parseSort(String sort) {
        String value = sort == null || sort.isBlank() ? "createdAt,desc" : sort.trim();
        String[] parts = value.split(",", -1);
        if (parts.length != 2) {
            throw validation("sort 参数不正确");
        }
        String direction = parts[1].toLowerCase(Locale.ROOT);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw validation("sort 方向只能是 asc 或 desc");
        }
        boolean ascending = "asc".equals(direction);
        return switch (parts[0]) {
            case "createdAt" -> ascending ? TaskSort.CREATED_ASC : TaskSort.CREATED_DESC;
            case "updatedAt" -> ascending ? TaskSort.UPDATED_ASC : TaskSort.UPDATED_DESC;
            case "confirmedAt" -> ascending ? TaskSort.CONFIRMED_ASC : TaskSort.CONFIRMED_DESC;
            default -> throw validation("sort 参数不正确");
        };
    }

    private <T> T inTenant(UUID userId, Supplier<T> work) {
        return transactions.execute(status -> tenants.execute(userId, work));
    }

    private DeliveryModels.TaskListItem toListItem(TaskListRow row) {
        return new DeliveryModels.TaskListItem(
                row.id(), row.status(), row.greeting(), row.version(), row.confirmationVersion(),
                row.confirmedAt(), row.job(), row.match(), row.device(), row.lastEvent(),
                row.createdAt(), row.updatedAt()
        );
    }

    private DeliveryModels.EventView toEventView(EventRow row) {
        return new DeliveryModels.EventView(
                row.id(), row.eventType(), row.fromStatus(), row.toStatus(),
                row.actorType(), row.createdAt(), row.details()
        );
    }

    private DeliveryModels.TaskView toView(TaskRecord task) {
        return new DeliveryModels.TaskView(
                task.id(), task.jobPostId(), task.jobMatchId(), task.status(), task.greeting(),
                task.version(), task.confirmationVersion(), task.confirmedAt(), task.createdAt()
        );
    }

    /** Job pool integration: latest/active task per job. */
    public Map<UUID, DeliveryModels.TaskStatusRef> taskStatusByJob(UUID userId, List<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        return inTenant(userId, () -> {
            Map<UUID, DeliveryModels.TaskStatusRef> result = new LinkedHashMap<>();
            for (TaskStatusRow row : tasks.findLatestTasksByJobIds(userId, jobIds)) {
                result.put(row.jobPostId(), new DeliveryModels.TaskStatusRef(
                        row.id(), row.status(), row.createdAt(), row.confirmedAt()
                ));
            }
            return result;
        });
    }

    public DeliveryModels.TaskDetailRef taskDetailByJob(UUID userId, UUID jobPostId) {
        return inTenant(userId, () -> tasks.findLatestTaskByJob(userId, jobPostId)
                .map(this::toDetailRef)
                .orElse(null)
        );
    }

    private DeliveryModels.TaskDetailRef toDetailRef(TaskDetailRow row) {
        return new DeliveryModels.TaskDetailRef(
                row.id(), row.status(), row.greeting(), row.version(), row.confirmationVersion(),
                row.confirmedAt(), row.createdAt(), row.finishedAt()
        );
    }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
