CREATE TABLE ticket_triage
(
    ticket_id           uuid            PRIMARY KEY REFERENCES tickets(id),
    order_number        text            NULL,
    category            text            NOT NULL CHECK (category IN ('ORDER', 'BILLING', 'RETURN_REFUND', 'ACCOUNT', 'FEEDBACK', 'OTHER')),
    priority            text            NOT NULL CHECK (priority IN ('URGENT', 'HIGH', 'MEDIUM', 'LOW')),
    escalate            BOOLEAN         NOT NULL DEFAULT FALSE,
    escalate_reason     text            NULL,
    summary             text            NOT NULL,
    model               text            NOT NULL,
    usd_cost            numeric(12,8)   NOT NULL,
    prompt_version      int             NOT NULL,
    input_tokens        int             NOT NULL,
    output_tokens       int             NOT NULL,
    reasoning_tokens    int             NULL,
    latency_ms          int             NOT NULL,
    created_at          timestamptz     NOT NULL,


    CHECK (escalate OR escalate_reason IS NULL)
);