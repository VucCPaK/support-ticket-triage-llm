CREATE TABLE ticket_draft_reply
(
    ticket_id           uuid            PRIMARY KEY REFERENCES ticket_triage(ticket_id),
    order_context       text            NOT NULL,
    reply_text          text            NOT NULL,
    model               text            NOT NULL,
    usd_cost            numeric(12,8)   NOT NULL,
    prompt_version      int             NOT NULL,
    input_tokens        int             NOT NULL,
    output_tokens       int             NOT NULL,
    reasoning_tokens    int             NULL,
    latency_ms          int             NOT NULL,
    created_at          timestamptz     NOT NULL
);