-- Keep refund rejection identities independent from unrelated transfer/funding identities.
CREATE OR REPLACE FUNCTION check_refund_state() RETURNS trigger LANGUAGE plpgsql AS $$
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
    ELSIF t.payment_id IS NOT NULL AND t.kind='REFUND' THEN
        RAISE EXCEPTION 'rejected refund cannot have a posting decision' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
