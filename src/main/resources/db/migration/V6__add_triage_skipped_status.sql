-- TRIAGE_SKIPPED: the daily LLM budget was used up, so the ticket was saved without calling the model
ALTER TABLE tickets DROP CONSTRAINT tickets_status_check;
ALTER TABLE tickets ADD CONSTRAINT tickets_status_check
    CHECK (status IN ('NEW', 'TRIAGED', 'TRIAGE_FAILED', 'TRIAGE_SKIPPED'));
