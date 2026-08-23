package com.getjobs.cloud.match;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@Profile("api")
public class MatchOutboxRepository {

    private final JdbcTemplate jdbc;

    public MatchOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID userId, UUID matchId, String eventType, String eventKey) {
        jdbc.update(
                """
                INSERT INTO app.job_match_outbox(user_id, job_match_id, event_type, event_key)
                VALUES (?, ?, CAST(? AS varchar), ?)
                ON CONFLICT (event_key) DO NOTHING
                """,
                userId, matchId, eventType, eventKey
        );
    }
}
