package com.getjobs.cloud.resume;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("api")
public class ResumeRepository {
    private static final String COLUMNS = """
            id, user_id, original_filename, storage_key, content_type, file_size, sha256,
            parse_status, parse_message, extracted_text_ciphertext, extracted_text_nonce,
            encryption_key_id, text_version, is_current, version, parsed_at, deleted_at,
            purged_at, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public ResumeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockUser(UUID userId) {
        jdbc.queryForObject("SELECT id FROM app.users WHERE id = ? FOR UPDATE", UUID.class, userId);
    }

    public void clearCurrent(UUID userId) {
        jdbc.update(
                "UPDATE app.resumes SET is_current=false, version=version+1 WHERE user_id=? AND is_current AND deleted_at IS NULL",
                userId
        );
    }

    public ResumeRecord insert(
            UUID id,
            UUID userId,
            String filename,
            String storageKey,
            String contentType,
            long fileSize,
            String sha256,
            String uploadKeyHash,
            String encryptionKeyId,
            boolean current
    ) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.resumes(
                    id, user_id, original_filename, storage_key, content_type, file_size,
                    sha256, upload_idempotency_key_hash, encryption_key_id, is_current
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING %s
                """.formatted(COLUMNS),
                this::map,
                id, userId, filename, storageKey, contentType, fileSize,
                sha256, uploadKeyHash, encryptionKeyId, current
        );
    }

    public Optional<ResumeRecord> findByUploadKey(UUID userId, String keyHash) {
        return optional("SELECT " + COLUMNS + " FROM app.resumes WHERE user_id=? AND upload_idempotency_key_hash=?", userId, keyHash);
    }

    public Optional<ResumeRecord> findCurrent(UUID userId) {
        return optional(
                "SELECT " + COLUMNS + " FROM app.resumes WHERE user_id=? AND is_current AND deleted_at IS NULL",
                userId
        );
    }

    public Optional<ResumeRecord> findByIdForUpdate(UUID userId, UUID id) {
        return optional(
                "SELECT " + COLUMNS + " FROM app.resumes WHERE user_id=? AND id=? FOR UPDATE",
                userId, id
        );
    }

    public List<ResumeRecord> list(UUID userId, int limit, int offset) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM app.resumes WHERE user_id=? AND deleted_at IS NULL ORDER BY created_at DESC LIMIT ? OFFSET ?",
                this::map,
                userId, limit, offset
        );
    }

    public long count(UUID userId) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM app.resumes WHERE user_id=? AND deleted_at IS NULL",
                Long.class,
                userId
        );
        return value == null ? 0 : value;
    }

    public ResumeRecord markDeleted(UUID userId, UUID id, Instant deletedAt) {
        return jdbc.queryForObject(
                """
                UPDATE app.resumes
                SET is_current=false,
                    deleted_at=?,
                    extracted_text_ciphertext=NULL,
                    extracted_text_nonce=NULL,
                    parse_lease_token=NULL,
                    parse_lease_until=NULL,
                    version=version+1
                WHERE user_id=? AND id=?
                RETURNING %s
                """.formatted(COLUMNS),
                this::map,
                Timestamp.from(deletedAt), userId, id
        );
    }

    private Optional<ResumeRecord> optional(String sql, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, this::map, args));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private ResumeRecord map(ResultSet rs, int row) throws SQLException {
        return new ResumeRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("original_filename"),
                rs.getString("storage_key"),
                rs.getString("content_type"),
                rs.getLong("file_size"),
                rs.getString("sha256"),
                rs.getString("parse_status"),
                rs.getString("parse_message"),
                rs.getBytes("extracted_text_ciphertext"),
                rs.getBytes("extracted_text_nonce"),
                rs.getString("encryption_key_id"),
                rs.getInt("text_version"),
                rs.getBoolean("is_current"),
                rs.getInt("version"),
                instant(rs, "parsed_at"),
                instant(rs, "deleted_at"),
                instant(rs, "purged_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
