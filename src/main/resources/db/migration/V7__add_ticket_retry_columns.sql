-- Retry bookkeeping for the recovery job: tickets left in NEW (the app stopped mid-call),
-- TRIAGE_FAILED (the model was down) or TRIAGE_SKIPPED (budget) are triaged again later.
ALTER TABLE tickets ADD COLUMN attempts        int         NOT NULL DEFAULT 0;
ALTER TABLE tickets ADD COLUMN next_attempt_at timestamptz NULL;     -- not before: max(lease, backoff)
ALTER TABLE tickets ADD COLUMN last_error      text        NULL;
-- Optimistic locking (JPA @Version): a worker whose lease expired and whose ticket was claimed again
-- cannot overwrite the newer result. Native updates of tickets must increment it too.
ALTER TABLE tickets ADD COLUMN version         bigint      NOT NULL DEFAULT 0;

-- The job looks only at tickets that are not done yet
CREATE INDEX idx_tickets_retry ON tickets (next_attempt_at) WHERE status IN ('NEW', 'TRIAGE_FAILED', 'TRIAGE_SKIPPED');
