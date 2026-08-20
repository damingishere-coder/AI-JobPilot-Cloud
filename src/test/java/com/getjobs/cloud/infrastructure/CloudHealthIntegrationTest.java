package com.getjobs.cloud.infrastructure;

import com.getjobs.cloud.CloudApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles({"cloud", "api", "test"})
@SpringBootTest(
        classes = CloudApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/postgresql",
                "spring.flyway.default-schema=app",
                "spring.flyway.schemas=app",
                "spring.flyway.create-schemas=true",
                "spring.flyway.placeholders.app_role=jobpilot_owner",
                "app.auth.hash-pepper=integration-test-auth-pepper-32-bytes",
                "spring.data.redis.connect-timeout=500ms",
                "spring.data.redis.timeout=500ms"
        }
)
class CloudHealthIntegrationTest {
    private static final String REDIS_PASSWORD = "integration_redis_password";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "ai-jobpilot-health-" + UUID.randomUUID()
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_jobpilot")
            .withUsername("jobpilot_owner")
            .withPassword("integration-owner-password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&connectTimeout=1&socketTimeout=1");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "500");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void separatesLivenessFromDependencyReadinessAndKeepsPostgresFacts() throws Exception {
        assertStatus("/livez", HttpStatus.OK);
        assertStatus("/readyz", HttpStatus.OK);
        jdbc.execute("CREATE TABLE IF NOT EXISTS app.health_fact_probe(id bigint primary key)");
        jdbc.update("INSERT INTO app.health_fact_probe(id) VALUES (1) ON CONFLICT DO NOTHING");

        pause(REDIS);
        try {
            awaitStatus("/readyz", HttpStatus.SERVICE_UNAVAILABLE, Duration.ofSeconds(20));
            assertStatus("/livez", HttpStatus.OK);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.health_fact_probe", Long.class)).isEqualTo(1L);
        } finally {
            unpause(REDIS);
        }
        awaitStatus("/readyz", HttpStatus.OK, Duration.ofSeconds(60));

        pause(POSTGRES);
        try {
            awaitStatus("/readyz", HttpStatus.SERVICE_UNAVAILABLE, Duration.ofSeconds(30));
            assertStatus("/livez", HttpStatus.OK);
        } finally {
            unpause(POSTGRES);
        }
        awaitStatus("/readyz", HttpStatus.OK, Duration.ofSeconds(60));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.health_fact_probe", Long.class)).isEqualTo(1L);
    }

    private void assertStatus(String path, HttpStatus expected) {
        assertThat(rest.getForEntity(path, Map.class).getStatusCode()).isEqualTo(expected);
    }

    private void awaitStatus(String path, HttpStatus expected, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        HttpStatus last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                last = HttpStatus.valueOf(rest.getForEntity(path, Map.class).getStatusCode().value());
                if (last == expected) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // 依赖重启期间短暂连接失败属于预期，继续轮询。
            }
            Thread.sleep(500);
        }
        assertThat(last).as("等待健康状态 %s", expected).isEqualTo(expected);
    }

    private static void pause(org.testcontainers.containers.Container<?> container) {
        DockerClientFactory.instance().client().pauseContainerCmd(container.getContainerId()).exec();
    }

    private static void unpause(org.testcontainers.containers.Container<?> container) {
        DockerClientFactory.instance().client().unpauseContainerCmd(container.getContainerId()).exec();
    }
}
