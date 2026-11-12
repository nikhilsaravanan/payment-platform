CREATE TABLE refunds (
    id uuid PRIMARY KEY,
    idempotency_key varchar(200) COLLATE "C" NOT NULL UNIQUE,
    payment_id uuid NOT NULL REFERENCES payments(id),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000),
    currency varchar(3) NOT NULL CHECK (currency='USD'),
    status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','REJECTED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    rejected_at timestamptz,
    rejection_reason varchar(32),
    CHECK ((status='COMPLETED') = (completed_at IS NOT NULL)),
    CHECK ((status='REJECTED' AND rejected_at IS NOT NULL AND rejection_reason IS NOT NULL
            AND rejection_reason IN ('INSUFFICIENT_FUNDS','PAYMENT_NOT_FOUND','PAYMENT_NOT_REFUNDABLE','REFUND_LIMIT_EXCEEDED'))
        OR (status<>'REJECTED' AND rejected_at IS NULL AND rejection_reason IS NULL))
);
CREATE INDEX due_refunds ON refunds(next_attempt_at,created_at) WHERE status='PENDING';
