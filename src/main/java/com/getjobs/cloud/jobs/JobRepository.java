package com.getjobs.cloud.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("api")
public class JobRepository {
    private static final String DETAIL_COLUMNS = """
            id, platform, external_job_id, title, company_name, salary_text, salary_min_k,
            salary_max_k, salary_months, location, experience, degree, description, job_url,
            company_info::text, skills::text, welfare::text, status, source_captured_at, last_seen_at
            """;

    /**
     * Fixed list/count SQL. Every filter is always present and guarded by a
     * boolean bind parameter, so a request value can never change the SQL
     * text; only JDBC bind values vary at runtime. Disabled filters bind
     * explicit placeholder values because PostgreSQL cannot infer the type of
     * a NULL bind. Match filters only consider each job's latest match
     * (created_at DESC, id DESC), otherwise an old APPLY record would shadow
     * a newer SKIP decision. The COUNT_SQL WHERE block must stay identical to
     * the LIST_SQL WHERE block.
     */
    private static final String COUNT_SQL = """
            SELECT count(*) FROM app.job_posts
            WHERE user_id=:userId
              AND (:filterPlatform=false OR platform=:platform)
              AND (:filterStatus=false OR status=:status)
              AND (:filterKeyword=false OR (title ILIKE :keyword OR company_name ILIKE :keyword OR location ILIKE :keyword))
              AND (:filterCapturedFrom=false OR source_captured_at>=:capturedFrom)
              AND (:filterCapturedTo=false OR source_captured_at<=:capturedTo)
              AND (:filterMatch=false OR EXISTS (
                    SELECT 1 FROM app.job_matches m
                    WHERE m.job_post_id=app.job_posts.id
                      AND m.user_id=:userId
                      AND m.id=(SELECT latest.id FROM app.job_matches latest
                                WHERE latest.user_id=:userId AND latest.job_post_id=app.job_posts.id
                                ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1)
                      AND (:filterMatchDecision=false OR m.decision=:matchDecision)
                      AND (:filterMatchStatus=false OR m.status=:matchStatus)
                      AND (:filterMinScore=false OR m.score>=:minScore)
                  ))
            """;

    /**
     * Sorting is resolved through the fixed CASE branches below: exactly one
     * branch is non-NULL for the bound :sort key, every other branch is NULL
     * (NULLS LAST), and id ASC is the stable tie-breaker.
     */
    private static final String LIST_SQL = """
            SELECT id, platform, title, company_name, salary_text, salary_min_k, salary_max_k,
                   salary_months, location, status, last_seen_at
            FROM app.job_posts
            WHERE user_id=:userId
              AND (:filterPlatform=false OR platform=:platform)
              AND (:filterStatus=false OR status=:status)
              AND (:filterKeyword=false OR (title ILIKE :keyword OR company_name ILIKE :keyword OR location ILIKE :keyword))
              AND (:filterCapturedFrom=false OR source_captured_at>=:capturedFrom)
              AND (:filterCapturedTo=false OR source_captured_at<=:capturedTo)
              AND (:filterMatch=false OR EXISTS (
                    SELECT 1 FROM app.job_matches m
                    WHERE m.job_post_id=app.job_posts.id
                      AND m.user_id=:userId
                      AND m.id=(SELECT latest.id FROM app.job_matches latest
                                WHERE latest.user_id=:userId AND latest.job_post_id=app.job_posts.id
                                ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1)
                      AND (:filterMatchDecision=false OR m.decision=:matchDecision)
                      AND (:filterMatchStatus=false OR m.status=:matchStatus)
                      AND (:filterMinScore=false OR m.score>=:minScore)
                  ))
            ORDER BY
              CASE WHEN :sort='LAST_SEEN_ASC' THEN last_seen_at END ASC NULLS LAST,
              CASE WHEN :sort='LAST_SEEN_DESC' THEN last_seen_at END DESC NULLS LAST,
              CASE WHEN :sort='CREATED_ASC' THEN created_at END ASC NULLS LAST,
              CASE WHEN :sort='CREATED_DESC' THEN created_at END DESC NULLS LAST,
              CASE WHEN :sort='SALARY_MIN_ASC' THEN salary_min_k END ASC NULLS LAST,
              CASE WHEN :sort='SALARY_MIN_DESC' THEN salary_min_k END DESC NULLS LAST,
              CASE WHEN :sort='TITLE_ASC' THEN title END ASC NULLS LAST,
              CASE WHEN :sort='TITLE_DESC' THEN title END DESC NULLS LAST,
              id ASC
            LIMIT :limit OFFSET :offset
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JobRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long count(UUID userId, JobModels.Query query) {
        Long total = jdbc.queryForObject(COUNT_SQL, parameters(userId, query), Long.class);
        return total == null ? 0 : total;
    }

    public List<JobModels.JobSummary> list(UUID userId, JobModels.Query query) {
        MapSqlParameterSource parameters = parameters(userId, query)
                .addValue("limit", query.size())
                .addValue("offset", (query.page() - 1) * query.size());
        return jdbc.query(LIST_SQL, parameters, this::mapSummary);
    }

    /**
     * Resolves the finite sort enum to the matching hard-coded CASE branch key
     * inside the fixed LIST_SQL. The resolved key only ever travels as a bind
     * parameter; no client text can reach the SQL string.
     */
    private static String sortKey(JobModels.JobSort sort) {
        if (sort == null) {
            return "LAST_SEEN_DESC";
        }
        return switch (sort) {
            case LAST_SEEN_ASC -> "LAST_SEEN_ASC";
            case LAST_SEEN_DESC -> "LAST_SEEN_DESC";
            case CREATED_ASC -> "CREATED_ASC";
            case CREATED_DESC -> "CREATED_DESC";
            case SALARY_MIN_ASC -> "SALARY_MIN_ASC";
            case SALARY_MIN_DESC -> "SALARY_MIN_DESC";
            case TITLE_ASC -> "TITLE_ASC";
            case TITLE_DESC -> "TITLE_DESC";
        };
    }

    /**
     * Binds every filter exactly once as an enable flag plus an explicit
     * value. Disabled filters carry fixed placeholder values so PostgreSQL
     * always sees a typed parameter, never NULL.
     */
    private static MapSqlParameterSource parameters(UUID userId, JobModels.Query query) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("userId", userId);
        parameters.addValue("filterPlatform", query.platform() != null);
        parameters.addValue("platform", query.platform() == null ? "" : query.platform());
        parameters.addValue("filterStatus", query.status() != null);
        parameters.addValue("status", query.status() == null ? "" : query.status());
        parameters.addValue("filterKeyword", query.keyword() != null);
        parameters.addValue("keyword", query.keyword() == null ? "" : "%" + query.keyword() + "%");
        parameters.addValue("filterCapturedFrom", query.capturedFrom() != null);
        parameters.addValue("capturedFrom", java.sql.Timestamp.from(
                query.capturedFrom() == null ? Instant.EPOCH : query.capturedFrom()));
        parameters.addValue("filterCapturedTo", query.capturedTo() != null);
        parameters.addValue("capturedTo", java.sql.Timestamp.from(
                query.capturedTo() == null ? Instant.EPOCH : query.capturedTo()));
        boolean matchFilter = query.matchDecision() != null || query.matchStatus() != null || query.minScore() != null;
        parameters.addValue("filterMatch", matchFilter);
        parameters.addValue("filterMatchDecision", query.matchDecision() != null);
        parameters.addValue("matchDecision", query.matchDecision() == null ? "" : query.matchDecision());
        parameters.addValue("filterMatchStatus", query.matchStatus() != null);
        parameters.addValue("matchStatus", query.matchStatus() == null ? "" : query.matchStatus());
        parameters.addValue("filterMinScore", query.minScore() != null);
        parameters.addValue("minScore", query.minScore() == null ? 0 : query.minScore());
        parameters.addValue("sort", sortKey(query.sort()));
        return parameters;
    }

    /**
     * Returns the subset of job IDs visible to the given user (RLS-gated).
     * Used by batch analyze to validate ownership without leaking other users' IDs.
     */
    public List<UUID> findVisibleJobIds(UUID userId, List<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        var parameters = new MapSqlParameterSource("userId", userId);
        List<String> placeholders = new java.util.ArrayList<>();
        for (int i = 0; i < jobIds.size(); i++) {
            String paramName = "id" + i;
            parameters.addValue(paramName, jobIds.get(i));
            placeholders.add(":" + paramName);
        }
        return jdbc.query(
                "SELECT id FROM app.job_posts WHERE user_id=:userId AND id IN (" +
                        String.join(",", placeholders) + ")",
                parameters,
                (rs, row) -> rs.getObject("id", UUID.class)
        );
    }

    public Optional<JobModels.JobDetail> find(UUID userId, UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + DETAIL_COLUMNS + " FROM app.job_posts WHERE user_id=:userId AND id=:id",
                    new MapSqlParameterSource().addValue("userId", userId).addValue("id", id),
                    this::mapDetail
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private JobModels.JobSummary mapSummary(ResultSet rs, int row) throws SQLException {
        return new JobModels.JobSummary(
                rs.getObject("id", UUID.class),
                rs.getString("platform"),
                rs.getString("title"),
                rs.getString("company_name"),
                salary(rs),
                rs.getString("location"),
                rs.getString("status"),
                null,
                null,
                rs.getTimestamp("last_seen_at").toInstant()
        );
    }

    private JobModels.JobDetail mapDetail(ResultSet rs, int row) throws SQLException {
        return new JobModels.JobDetail(
                rs.getObject("id", UUID.class),
                rs.getString("platform"),
                rs.getString("external_job_id"),
                rs.getString("title"),
                rs.getString("company_name"),
                salary(rs),
                rs.getString("location"),
                rs.getString("experience"),
                rs.getString("degree"),
                rs.getString("description"),
                rs.getString("job_url"),
                map(rs.getString("company_info")),
                strings(rs.getString("skills")),
                strings(rs.getString("welfare")),
                rs.getString("status"),
                rs.getTimestamp("source_captured_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                null,
                null
        );
    }

    private JobModels.Salary salary(ResultSet rs) throws SQLException {
        Integer months = rs.getObject("salary_months", Integer.class);
        return new JobModels.Salary(
                rs.getBigDecimal("salary_min_k"),
                rs.getBigDecimal("salary_max_k"),
                months,
                rs.getString("salary_text")
        );
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取岗位数组字段", exception);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取岗位公司字段", exception);
        }
    }
}
