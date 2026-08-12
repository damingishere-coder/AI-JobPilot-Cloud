package com.getjobs.cloud.preference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.preference.PreferenceRepository.NormalizedPreference;
import com.getjobs.cloud.preference.PreferenceRepository.PreferenceRecord;
import com.getjobs.cloud.tenant.TenantContextExecutor;
import com.getjobs.cloud.web.ApiError;
import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Profile("api")
public class PreferenceService {
    private final PreferenceRepository preferences;
    private final TenantContextExecutor tenants;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public PreferenceService(
            PreferenceRepository preferences,
            TenantContextExecutor tenants,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.preferences = preferences;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public PreferenceModels.PreferenceView current(UUID userId) {
        return inTenant(userId, () -> preferences.findCurrent(userId, false).map(this::view).orElse(null));
    }

    public PreferenceModels.PreferenceView update(UUID userId, PreferenceModels.UpdateRequest request) {
        return inTenant(userId, () -> {
            preferences.lockUser(userId);
            PreferenceRecord current = preferences.findCurrent(userId, true).orElse(null);
            if (current == null) {
                if (request.version() != null) {
                    throw conflict();
                }
            } else if (request.version() == null || request.version() != current.version()) {
                throw conflict();
            }
            int nextVersion = current == null ? 1 : current.version() + 1;
            NormalizedPreference normalized = normalize(request, current);
            if (current != null) {
                preferences.clearCurrent(userId);
            }
            return view(preferences.insert(userId, nextVersion, normalized));
        });
    }

    private static final short DEFAULT_REVIEW = 60;
    private static final short DEFAULT_PRIORITY = 65;
    private static final short DEFAULT_APPLY = 75;

    private NormalizedPreference normalize(PreferenceModels.UpdateRequest request, PreferenceRecord current) {
        if (request == null) {
            throw validation("body", "请求内容不能为空");
        }
        List<String> titles = normalizeList("targetTitles", request.targetTitles(), 1, 10, 80);
        List<String> cities = normalizeList("cities", request.cities(), 0, 20, 80);
        List<String> experience = normalizeList("experienceLevels", request.experienceLevels(), 0, 20, 80);
        List<String> degrees = normalizeList("degreeLevels", request.degreeLevels(), 0, 20, 80);
        List<String> industries = normalizeList("industries", request.industries(), 0, 20, 80);
        List<String> scales = normalizeList("companyScales", request.companyScales(), 0, 20, 80);
        List<String> preferred = normalizeList("preferredCompanies", request.preferredCompanies(), 0, 50, 120);
        List<String> excluded = normalizeList("excludedCompanies", request.excludedCompanies(), 0, 50, 120);
        List<String> keywords = normalizeList("excludedKeywords", request.excludedKeywords(), 0, 50, 120);
        BigDecimal min = salary("salaryMinK", request.salaryMinK());
        BigDecimal max = salary("salaryMaxK", request.salaryMaxK());
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw validation("salaryMaxK", "最高薪资不能低于最低薪资");
        }
        Map<String, Object> extra;
        try {
            extra = request.extraFilters() == null ? Map.of() : Map.copyOf(request.extraFilters());
        } catch (NullPointerException exception) {
            throw validation("extraFilters", "扩展筛选内容不能包含空键或空值");
        }
        try {
            if (objectMapper.writeValueAsBytes(extra).length > 4096) {
                throw validation("extraFilters", "扩展筛选内容不能超过 4 KiB");
            }
        } catch (JsonProcessingException exception) {
            throw validation("extraFilters", "扩展筛选内容格式不正确");
        }
        // Threshold inheritance: null in request inherits from current record;
        // on first creation, use hard-coded defaults.
        short review = threshold("reviewThreshold", request.reviewThreshold(),
                current != null ? current.reviewThreshold() : DEFAULT_REVIEW);
        short priority = threshold("priorityApplyThreshold", request.priorityApplyThreshold(),
                current != null ? current.priorityApplyThreshold() : DEFAULT_PRIORITY);
        short apply = threshold("applyThreshold", request.applyThreshold(),
                current != null ? current.applyThreshold() : DEFAULT_APPLY);
        if (review > priority || priority > apply) {
            throw validation("reviewThreshold",
                    "推荐阈值必须满足：查看 ≤ 优先投递 ≤ 普通投递，每个值在 0-100 之间");
        }
        return new NormalizedPreference(
                titles, cities, min, max, experience, degrees, industries, scales,
                preferred, excluded, keywords, extra, review, priority, apply
        );
    }

    private short threshold(String field, Integer value, short defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value < 0 || value > 100) {
            throw validation(field, "阈值必须在 0 到 100 之间");
        }
        return value.shortValue();
    }

    private List<String> normalizeList(String field, List<String> values, int min, int max, int itemMax) {
        List<String> source = values == null ? List.of() : values;
        LinkedHashMap<String, String> deduplicated = new LinkedHashMap<>();
        for (String raw : source) {
            String value = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > itemMax) {
                throw validation(field, "单项内容不能超过 " + itemMax + " 个字符");
            }
            deduplicated.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        }
        List<String> result = new ArrayList<>(deduplicated.values());
        if (result.size() < min || result.size() > max) {
            String reason = min > 0
                    ? "必须填写 " + min + " 到 " + max + " 项"
                    : "最多填写 " + max + " 项";
            throw validation(field, reason);
        }
        return List.copyOf(result);
    }

    private BigDecimal salary(String field, BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal normalized = value.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(BigDecimal.ZERO) < 0 || normalized.compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw validation(field, "薪资必须在 0 到 1000K 之间");
        }
        return normalized;
    }

    private PreferenceModels.PreferenceView view(PreferenceRecord record) {
        return new PreferenceModels.PreferenceView(
                record.id(), record.version(), record.targetTitles(), record.cities(),
                record.salaryMinK(), record.salaryMaxK(), record.experienceLevels(),
                record.degreeLevels(), record.industries(), record.companyScales(),
                record.preferredCompanies(), record.excludedCompanies(), record.excludedKeywords(),
                record.extraFilters(), record.reviewThreshold(), record.priorityApplyThreshold(),
                record.applyThreshold(), record.updatedAt()
        );
    }

    private <T> T inTenant(UUID userId, Supplier<T> work) {
        return transactions.execute(status -> tenants.execute(userId, work));
    }

    private ApiException conflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "RESOURCE_VERSION_CONFLICT",
                "求职目标已在其他页面更新，请刷新后重试"
        );
    }

    private ApiException validation(String field, String reason) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "求职目标填写不正确",
                false,
                0,
                List.of(new ApiError.FieldViolation(field, reason))
        );
    }
}
