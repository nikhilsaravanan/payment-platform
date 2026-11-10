ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check CHECK (status IN ('PENDING','COMPLETED','REJECTED'));
ALTER TABLE payments ADD COLUMN rejected_at timestamptz;
ALTER TABLE payments ADD COLUMN rejection_reason varchar(24);
ALTER TABLE payments ADD CONSTRAINT payment_rejection_fields CHECK (
    (status='REJECTED' AND rejected_at IS NOT NULL AND rejection_reason IS NOT NULL
        AND rejection_reason IN ('INSUFFICIENT_FUNDS','ACCOUNT_NOT_FOUND'))
    OR (status<>'REJECTED' AND rejected_at IS NULL AND rejection_reason IS NULL)
);
