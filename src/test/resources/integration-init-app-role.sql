-- Creates the runtime application role inside the Testcontainers PostgreSQL
-- instance before Spring Boot connects, so HTTP-level integration tests run
-- with full row-level security like production.
CREATE ROLE jobpilot_app LOGIN PASSWORD 'integration-app-password';
GRANT CONNECT ON DATABASE ai_jobpilot TO jobpilot_app;
