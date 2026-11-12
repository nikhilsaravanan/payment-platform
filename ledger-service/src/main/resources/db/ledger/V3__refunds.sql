ALTER TABLE transfer_decisions DROP CONSTRAINT transfer_decisions_kind_check;
ALTER TABLE transfer_decisions ADD CONSTRAINT transfer_decisions_kind_check CHECK (kind IN ('TRANSFER','FUNDING','REFUND'));
ALTER TABLE transfer_decisions DROP CONSTRAINT transfer_decisions_check1;
ALTER TABLE transfer_decisions ADD CONSTRAINT movement_kind_check CHECK (
    (kind IN ('TRANSFER','REFUND') AND idempotency_key IS NULL AND source_account<>'__simulation_issuer__')
    OR (kind='FUNDING' AND idempotency_key IS NOT NULL AND source_account='__simulation_issuer__')
);
CREATE TABLE refund_decisions (
    refund_id uuid PRIMARY KEY,
    original_payment_id uuid NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000),
    currency varchar(3) NOT NULL CHECK (currency='USD'),
    outcome varchar(40) NOT NULL CHECK (outcome IN ('PROCESSING','REFUND_POSTED','REFUND_INSUFFICIENT_FUNDS',
        'REFUND_PAYMENT_NOT_FOUND','REFUND_PAYMENT_NOT_REFUNDABLE','REFUND_LIMIT_EXCEEDED')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX posted_refunds_by_original ON refund_decisions(original_payment_id) WHERE outcome='REFUND_POSTED';

CREATE FUNCTION guard_refund_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.outcome<>'PROCESSING' OR
       ROW(OLD.refund_id,OLD.original_payment_id,OLD.amount_minor,OLD.currency,OLD.created_at) IS DISTINCT FROM
       ROW(NEW.refund_id,NEW.original_payment_id,NEW.amount_minor,NEW.currency,NEW.created_at) THEN
        RAISE EXCEPTION 'refund decisions are immutable after resolution' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_refund_decisions BEFORE UPDATE ON refund_decisions
    FOR EACH ROW EXECUTE FUNCTION guard_refund_decision();
CREATE TRIGGER no_refund_delete BEFORE DELETE ON refund_decisions
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE FUNCTION check_refund_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r refund_decisions%ROWTYPE; p journals%ROWTYPE; t transfer_decisions%ROWTYPE;
BEGIN
    SELECT * INTO STRICT r FROM refund_decisions WHERE refund_id=NEW.refund_id;
    IF r.outcome='PROCESSING' THEN
        RAISE EXCEPTION 'refund must have a final decision' USING ERRCODE='23514';
    END IF;
    SELECT * INTO t FROM transfer_decisions WHERE payment_id=r.refund_id;
    IF r.outcome IN ('REFUND_POSTED','REFUND_INSUFFICIENT_FUNDS') THEN
        SELECT j.* INTO p FROM journals j JOIN transfer_decisions d USING(payment_id)
            WHERE j.payment_id=r.original_payment_id AND d.kind='TRANSFER' AND d.outcome='POSTED';
        IF p.payment_id IS NULL OR t.payment_id IS NULL OR t.kind<>'REFUND' OR
           ROW(t.source_account,t.destination_account,t.amount_minor,t.currency) IS DISTINCT FROM
           ROW(p.destination_account,p.source_account,r.amount_minor,r.currency) OR
           t.outcome<>(CASE r.outcome WHEN 'REFUND_POSTED' THEN 'POSTED' ELSE 'INSUFFICIENT_FUNDS' END) THEN
            RAISE EXCEPTION 'refund must match a reversal of a posted payment' USING ERRCODE='23514';
        END IF;
        IF r.outcome='REFUND_POSTED' AND
           (SELECT sum(amount_minor::numeric) FROM refund_decisions
                WHERE original_payment_id=r.original_payment_id AND outcome='REFUND_POSTED')>p.amount_minor THEN
            RAISE EXCEPTION 'refund total exceeds original payment' USING ERRCODE='23514';
        END IF;
    ELSIF t.payment_id IS NOT NULL THEN
        RAISE EXCEPTION 'rejected refund cannot have a posting decision' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_is_final AFTER INSERT OR UPDATE ON refund_decisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_refund_state();

CREATE FUNCTION check_refund_posting_link() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t transfer_decisions%ROWTYPE;
BEGIN
    SELECT * INTO STRICT t FROM transfer_decisions WHERE payment_id=NEW.payment_id;
    IF NOT EXISTS (SELECT 1 FROM refund_decisions r WHERE r.refund_id=t.payment_id AND
        ((r.outcome='REFUND_POSTED' AND t.outcome='POSTED') OR
         (r.outcome='REFUND_INSUFFICIENT_FUNDS' AND t.outcome='INSUFFICIENT_FUNDS'))) THEN
        RAISE EXCEPTION 'refund posting must have a matching refund decision' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_posting_link AFTER INSERT OR UPDATE ON transfer_decisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.kind='REFUND') EXECUTE FUNCTION check_refund_posting_link();
