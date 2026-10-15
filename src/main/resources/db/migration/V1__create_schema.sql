CREATE TABLE customers (
    id          uuid        PRIMARY KEY,
    email       text        NOT NULL UNIQUE,
    name        text        NOT NULL,
    plan        text        NOT NULL CHECK (plan IN ('FREE', 'PRO', 'ENTERPRISE')),
    created_at  timestamptz NOT NULL
);

CREATE TABLE orders (
    id            uuid          PRIMARY KEY,
    order_number  text          NOT NULL UNIQUE,
    customer_id   uuid          NOT NULL REFERENCES customers (id),
    status        text          NOT NULL CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED')),
    total_amount  numeric(10,2) NOT NULL CHECK (total_amount >= 0),
    currency      text          NOT NULL CHECK (char_length(currency) = 3),
    eta           date,
    created_at    timestamptz   NOT NULL
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);

-- customer_email is not a foreign key: tickets may come from unknown addresses
CREATE TABLE tickets (
    id              uuid        PRIMARY KEY,
    customer_email  text        NOT NULL,
    subject         text        NOT NULL,
    body            text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('NEW', 'TRIAGED', 'TRIAGE_FAILED')),
    created_at      timestamptz NOT NULL
);

CREATE INDEX idx_tickets_status_created_at ON tickets (status, created_at DESC);
