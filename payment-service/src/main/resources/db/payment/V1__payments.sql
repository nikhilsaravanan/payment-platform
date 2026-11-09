CREATE TABLE payments (
    id uuid PRIMARY KEY,
    idempotency_key varchar(200) COLLATE "C" NOT NULL UNIQUE,
    source_account varchar(100) NOT NULL CHECK (length(btrim(source_account))>0),
    destination_account varchar(100) NOT NULL CHECK (length(btrim(destination_account))>0),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000),
    currency varchar(3) NOT NULL CHECK (currency='USD'),
    status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK (source_account <> destination_account),
    CHECK ((status='COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX due_payments ON payments(next_attempt_at,created_at) WHERE status='PENDING';
