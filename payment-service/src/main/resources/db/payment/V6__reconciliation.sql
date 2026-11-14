CREATE TABLE reconciliation_reports (
    id uuid PRIMARY KEY,
    idempotency_key varchar(200) COLLATE "C" NOT NULL UNIQUE,
    account_id varchar(100) NOT NULL CHECK (length(btrim(account_id))>0),
    window_from timestamptz NOT NULL,
    window_to timestamptz NOT NULL CHECK (window_to>window_from AND window_to-window_from<=interval '31 days'),
    captured_at timestamptz NOT NULL,
    processor_count integer NOT NULL CHECK (processor_count BETWEEN 0 AND 1000),
    internal_count integer NOT NULL CHECK (internal_count BETWEEN 0 AND 1000),
    matched_count integer NOT NULL CHECK (matched_count BETWEEN 0 AND processor_count),
    finding_count integer NOT NULL CHECK (finding_count BETWEEN 0 AND 6000),
    request jsonb NOT NULL CHECK (jsonb_typeof(request)='object'),
    internal_snapshot jsonb NOT NULL CHECK (jsonb_typeof(internal_snapshot)='array'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE reconciliation_findings (
    report_id uuid NOT NULL REFERENCES reconciliation_reports(id),
    ordinal integer NOT NULL CHECK (ordinal>0),
    code varchar(32) NOT NULL CHECK (code IN ('DUPLICATE_OPERATION','DUPLICATE_REFERENCE','MISSING_INTERNAL',
        'OUT_OF_SCOPE','INTERNAL_NOT_COMPLETED','AMOUNT_MISMATCH','CURRENCY_MISMATCH','MISSING_PROCESSOR')),
    operation_type varchar(7) NOT NULL CHECK (operation_type IN ('PAYMENT','REFUND')),
    operation_id uuid NOT NULL,
    processor_row integer CHECK (processor_row BETWEEN 1 AND 1000),
    expected jsonb,
    actual jsonb,
    PRIMARY KEY (report_id,ordinal),
    CHECK (expected IS NOT NULL OR actual IS NOT NULL)
);
CREATE FUNCTION reject_reconciliation_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'reconciliation audit records are immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_reconciliation_reports BEFORE UPDATE OR DELETE ON reconciliation_reports
    FOR EACH ROW EXECUTE FUNCTION reject_reconciliation_mutation();
CREATE TRIGGER immutable_reconciliation_findings BEFORE UPDATE OR DELETE ON reconciliation_findings
    FOR EACH ROW EXECUTE FUNCTION reject_reconciliation_mutation();
CREATE INDEX payment_reconciliation_scope ON payments(destination_account,created_at);
CREATE INDEX refund_reconciliation_scope ON refunds(payment_id,created_at);
