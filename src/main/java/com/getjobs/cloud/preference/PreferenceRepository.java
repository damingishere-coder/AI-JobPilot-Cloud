package com.getjobs.cloud.preference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("api")
public class PreferenceRepository {
    private static final String COLUMNS = """
            id, user_id, version, target_titles::text, cities::text, salary_min_k, salary_max_k,
            experience_levels::text, degree_levels::text, industries::text, company_scales::text,
            preferred_companies::text, excluded_companies::text, excluded_keywords::text,
            extra_filters::text, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PreferenceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void lockUser(UUID userId) {
        jdbc.queryForObject("SELECT id FROM app.users WHERE id=? FOR UPDATE", UUID.class, userId);
    }

    public Optional<PreferenceRecord> findCurrent(UUID userId, boolean forUpdate) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM app.job_preferences WHERE user_id=? AND is_current" + (forUpdate ? " FOR UPDATE" : ""),
                    this::map,
                    userId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public void clearCurrent(UUID userId) {
        jdbc.update("UPDATE app.job_preferences SET is_current=false WHERE user_id=? AND is_current", userId);
    }

    public PreferenceRecord insert(UUID userId, int version, NormalizedPreference value) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.job_preferences(
                    user_id, version, is_current, target_titles, cities, salary_min_k, salary_max_k,
                    experience_levels, degree_levels, industries, company_scales,
                    preferred_companies, excluded_companies, excluded_keywords, extra_filters
                ) VALUES (
                    ?, ?, true, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb)
                )
                RETURNING %s
                """.formatted(COLUMNS),
                this::map,
                userId,
                version,
                json(value.targetTitles()),
                json(value.cities()),
                value.salaryMinK(),
                value.salaryMaxK(),
                json(value.experienceLevels()),
                json(value.degreeLevels()),
                json(value.industries()),
                json(value.companyScales()),
                json(value.preferredCompanies()),
                json(value.excludedCompanies()),
                json(value.excludedKeywords()),
                json(value.extraFilters())
        );
    }

    private PreferenceRecord map(ResultSet rs, int row) throws SQLException {
        return new PreferenceRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getInt("version"),
                strings(rs.getString("target_titles")),
                strings(rs.getString("cities")),
                rs.getBigDecimal("salary_min_k"),
                rs.getBigDecimal("salary_max_k"),
                strings(rs.getString("experience_levels")),
                strings(rs.getString("degree_levels")),
                strings(rs.getString("industries")),
                strings(rs.getString("company_scales")),
                strings(rs.getString("preferred_companies")),
                strings(rs.getString("excluded_companies")),
                strings(rs.getString("excluded_keywords")),
                map(rs.getString("extra_filters")),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化求职目标", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取求职目标数组", exception);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取求职目标扩展字段", exception);
        }
    }

    record PreferenceRecord(
            UUID id,
            UUID userId,
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

    record NormalizedPreference(
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
}
