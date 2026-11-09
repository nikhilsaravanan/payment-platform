CREATE TABLE journals (
    payment_id uuid PRIMARY KEY,
    source_account varchar(100) NOT NULL CHECK (length(btrim(source_account)) > 0),
    destination_account varchar(100) NOT NULL CHECK (length(btrim(destination_account)) > 0),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000),
    currency varchar(3) NOT NULL CHECK (currency = 'USD'),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (source_account <> destination_account)
);

CREATE TABLE entries (
    payment_id uuid NOT NULL REFERENCES journals(payment_id),
    side varchar(6) NOT NULL CHECK (side IN ('DEBIT','CREDIT')),
    account varchar(100) NOT NULL CHECK (length(btrim(account)) > 0),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000),
    currency varchar(3) NOT NULL CHECK (currency = 'USD'),
    PRIMARY KEY (payment_id, side)
);

CREATE FUNCTION check_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j journals%ROWTYPE;
BEGIN
    SELECT * INTO STRICT j FROM journals WHERE payment_id = NEW.payment_id;
    IF (SELECT count(*) FROM entries WHERE payment_id = j.payment_id) <> 2
       OR (SELECT COALESCE(sum(CASE WHEN side='DEBIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END),0)
           FROM entries WHERE payment_id = j.payment_id) <> 0
       OR EXISTS (SELECT 1 FROM entries WHERE payment_id = j.payment_id AND
           (amount_minor <> j.amount_minor OR currency <> j.currency OR
            account <> CASE WHEN side='DEBIT' THEN j.source_account ELSE j.destination_account END)) THEN
        RAISE EXCEPTION 'journal must contain a matching balanced debit and credit' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;

-- Check both the header and entries: even a header with zero entries cannot commit.
CREATE CONSTRAINT TRIGGER journal_balance AFTER INSERT ON journals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal();
CREATE CONSTRAINT TRIGGER entry_balance AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal();

CREATE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'posted ledger records are immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER immutable_journals BEFORE UPDATE OR DELETE ON journals
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_entries BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
