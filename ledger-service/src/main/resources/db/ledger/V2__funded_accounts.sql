-- Preserve old history and its exact balances; do not invent funding.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM entries WHERE account='__simulation_issuer__') THEN
        RAISE EXCEPTION 'legacy account uses reserved simulation issuer ID; explicit migration required';
    END IF;
END $$;
CREATE TABLE accounts (
    account_id varchar(100) COLLATE "C" PRIMARY KEY CHECK (length(btrim(account_id))>0),
    currency varchar(3) NOT NULL CHECK (currency='USD'),
    kind varchar(20) NOT NULL CHECK (kind IN ('CUSTOMER','SIMULATION_ISSUER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((kind='SIMULATION_ISSUER') = (account_id='__simulation_issuer__'))
);
INSERT INTO accounts(account_id,currency,kind) SELECT DISTINCT account,currency,'CUSTOMER' FROM entries;
INSERT INTO accounts(account_id,currency,kind) VALUES ('__simulation_issuer__','USD','SIMULATION_ISSUER');
CREATE INDEX entries_by_account ON entries(account);
ALTER TABLE entries ADD CONSTRAINT entry_account_fk FOREIGN KEY (account) REFERENCES accounts(account_id);
ALTER TABLE journals ADD CONSTRAINT source_account_fk FOREIGN KEY (source_account) REFERENCES accounts(account_id);
ALTER TABLE journals ADD CONSTRAINT destination_account_fk FOREIGN KEY (destination_account) REFERENCES accounts(account_id);
CREATE TRIGGER immutable_accounts BEFORE UPDATE OR DELETE ON accounts
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TABLE transfer_decisions (
    payment_id uuid PRIMARY KEY,
    kind varchar(8) NOT NULL CHECK (kind IN ('TRANSFER','FUNDING')),
    idempotency_key varchar(200) COLLATE "C",
    source_account varchar(100) NOT NULL,
    destination_account varchar(100) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000),
    currency varchar(3) NOT NULL CHECK (currency='USD'),
    outcome varchar(24) NOT NULL CHECK (outcome IN ('PROCESSING','POSTED','INSUFFICIENT_FUNDS','ACCOUNT_NOT_FOUND')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(kind,idempotency_key),
    CHECK (source_account<>destination_account),
    CHECK ((kind='TRANSFER' AND idempotency_key IS NULL AND source_account<>'__simulation_issuer__')
        OR (kind='FUNDING' AND idempotency_key IS NOT NULL AND source_account='__simulation_issuer__')),
    CHECK (destination_account<>'__simulation_issuer__')
);
INSERT INTO transfer_decisions(payment_id,kind,source_account,destination_account,amount_minor,currency,outcome,created_at)
    SELECT payment_id,'TRANSFER',source_account,destination_account,amount_minor,currency,'POSTED',created_at FROM journals;
ALTER TABLE journals ADD CONSTRAINT journal_decision_fk FOREIGN KEY (payment_id)
    REFERENCES transfer_decisions(payment_id) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION guard_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.outcome<>'PROCESSING' OR
       ROW(OLD.payment_id,OLD.kind,OLD.idempotency_key,OLD.source_account,OLD.destination_account,OLD.amount_minor,OLD.currency,OLD.created_at)
       IS DISTINCT FROM
       ROW(NEW.payment_id,NEW.kind,NEW.idempotency_key,NEW.source_account,NEW.destination_account,NEW.amount_minor,NEW.currency,NEW.created_at) THEN
        RAISE EXCEPTION 'transfer decisions are immutable after resolution' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_decisions BEFORE UPDATE ON transfer_decisions FOR EACH ROW EXECUTE FUNCTION guard_decision();
CREATE TRIGGER no_decision_delete BEFORE DELETE ON transfer_decisions FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE FUNCTION check_decision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d transfer_decisions%ROWTYPE; j journals%ROWTYPE;
BEGIN
    SELECT * INTO STRICT d FROM transfer_decisions WHERE payment_id=NEW.payment_id;
    SELECT * INTO j FROM journals WHERE payment_id=d.payment_id;
    IF d.outcome='PROCESSING' OR
       (d.outcome='POSTED' AND (j.payment_id IS NULL OR
          ROW(j.source_account,j.destination_account,j.amount_minor,j.currency) IS DISTINCT FROM
          ROW(d.source_account,d.destination_account,d.amount_minor,d.currency))) OR
       (d.outcome<>'POSTED' AND j.payment_id IS NOT NULL) THEN
        RAISE EXCEPTION 'decision must be final and agree with its journal' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER decision_is_final AFTER INSERT OR UPDATE ON transfer_decisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_decision();
CREATE CONSTRAINT TRIGGER journal_matches_decision AFTER INSERT ON journals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_decision();
