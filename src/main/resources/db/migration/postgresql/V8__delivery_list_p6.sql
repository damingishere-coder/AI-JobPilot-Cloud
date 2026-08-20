-- Round 6 (P6): delivery list + user confirmation loop.
--
-- Key design decisions:
--   One delivery task per (user_id, job_match_id) forever: repeated or
--     concurrent creates for the same match resolve to the original task no
--     matter which Idempotency-Key was used. The partial unique index below
--     enforces this in the database; the service re-reads the winner instead
--     of surfacing the unique-index conflict.
--   Pre-existing duplicate rows are never rewritten automatically. A
--     duplicate means historical delivery state needs human review (for
--     example, one row may already represent a successful delivery). The
--     migration therefore fails with a clear message before creating the
--     unique index, preserving every task, status, match link and event.
--   Persistence status values stay unchanged (V6 CHECK); the P6 Web API maps
--     them to the user-facing P6 names (WAITING_CONFIRM / CONFIRMED / SKIPPED
--     / PAUSED_NEED_USER) and never exposes backend execution states
--     (LEASED / EXECUTING / SUCCEEDED / FAILED / CANCELLED).
-- 1. Fail closed on historical duplicates. Do not silently pick a winner or
--    mutate delivery history inside a schema migration.
DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM app.delivery_tasks
        WHERE job_match_id IS NOT NULL
        GROUP BY user_id, job_match_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = 'V8 blocked: duplicate delivery_tasks exist for (user_id, job_match_id)',
            HINT = 'Audit the duplicate delivery history and resolve it explicitly before retrying the migration.';
    END IF;
END
$migration$;

-- 2. One task per user + match, forever (terminal rows included).
CREATE UNIQUE INDEX delivery_tasks_user_match_unique
    ON app.delivery_tasks (user_id, job_match_id)
    WHERE job_match_id IS NOT NULL;

COMMENT ON INDEX delivery_tasks_user_match_unique IS
    'One delivery task per user + match forever; repeated creates for the same match resolve to the original task.';
