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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("api")
public class JobRepository {
    private static final String SUMMARY_COLUMNS = """
            id, platform, title, company_name, salary_text, salary_min_k, salary_max_k,
            salary_months, location, status, last_seen_at
            """;
    private static final String DETAIL_COLUMNS = """
            id, platform, external_job_id, title, company_name, salary_text, salary_min_k,
            salary_max_k, salary_months, location, experience, degree, description, job_url,
            company_info::text, skills::text, welfare::text, status, source_captured_at, last_seen_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JobRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long count(UUID userId, JobModels.Query query) {
        SqlParts parts = where(userId, query);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM app.job_posts " + parts.where(),
                parts.parameters(),
                Long.class
        );
        return total == null ? 0 : total;
    }

    public List<JobModels.JobSummary> list(UUID userId, JobModels.Query query) {
        SqlParts parts = where(userId, query);
        MapSqlParameterSource parameters = parts.parameters()
                .addValue("limit", query.size())
                .addValue("offset", (query.page() - 1) * query.size());
        return jdbc.query(
                "SELECT " + SUMMARY_COLUMNS + " FROM app.job_posts " + parts.where()
                        + " ORDER BY " + query.orderBy() + " LIMIT :limit OFFSET :offset",
                parameters,
                this::mapSummary
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

    private SqlParts where(UUID userId, JobModels.Query query) {
        StringBuilder sql = new StringBuilder("WHERE user_id=:userId");
        MapSqlParameterSource parameters = new MapSqlParameterSource("userId", userId);
        if (query.platform() != null) {
            sql.append(" AND platform=:platform");
            parameters.addValue("platform", query.platform());
        }
        if (query.status() != null) {
            sql.append(" AND status=:status");
            parameters.addValue("status", query.status());
        }
        if (query.keyword() != null) {
            sql.append(" AND (title ILIKE :keyword OR company_name ILIKE :keyword OR location ILIKE :keyword)");
            parameters.addValue("keyword", "%" + query.keyword() + "%");
        }
        if (query.capturedFrom() != null) {
            sql.append(" AND source_captured_at>=:capturedFrom");
            parameters.addValue("capturedFrom", query.capturedFrom());
        }
        if (query.capturedTo() != null) {
            sql.append(" AND source_captured_at<=:capturedTo");
            parameters.addValue("capturedTo", query.capturedTo());
        }
        return new SqlParts(sql.toString(), parameters);
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

    private record SqlParts(String where, MapSqlParameterSource parameters) {
    }
}
