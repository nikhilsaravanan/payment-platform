CREATE TABLE webhook_subscriptions (
    id uuid PRIMARY KEY,
    idempotency_key varchar(200) COLLATE "C" NOT NULL UNIQUE,
    account_id varchar(100) NOT NULL CHECK (length(btrim(account_id))>0),
    url varchar(2048) NOT NULL,
    signing_secret varchar(256) NOT NULL CHECK (length(signing_secret) BETWEEN 32 AND 256 AND signing_secret ~ '^[!-~]+$'),
    active boolean NOT NULL DEFAULT true,
    disabled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (active = (disabled_at IS NULL))
);
CREATE INDEX active_webhook_accounts ON webhook_subscriptions(account_id) WHERE active;
CREATE TABLE webhook_events (
    id uuid PRIMARY KEY,
    event_type varchar(24) NOT NULL CHECK (event_type IN ('payment.completed','payment.rejected','refund.completed','refund.rejected')),
    resource_type varchar(7) NOT NULL CHECK (resource_type IN ('PAYMENT','REFUND')),
    resource_id uuid NOT NULL,
    account_id varchar(100) NOT NULL,
    occurred_at timestamptz NOT NULL,
    payload text NOT NULL,
    UNIQUE(resource_type,resource_id),
    CHECK ((jsonb_typeof(payload::jsonb)='object' AND payload::jsonb->>'id'=id::text
        AND payload::jsonb->>'type'=event_type AND payload::jsonb->>'accountId'=account_id) IS TRUE)
);
CREATE TABLE webhook_deliveries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL REFERENCES webhook_events(id),
    subscription_id uuid NOT NULL REFERENCES webhook_subscriptions(id),
    status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_FLIGHT','DELIVERED','FAILED','CANCELED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 20),
    next_attempt_at timestamptz DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    last_http_status integer CHECK (last_http_status BETWEEN 100 AND 999),
    last_error varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE(event_id,subscription_id),
    CHECK ((status='PENDING' AND next_attempt_at IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL AND finished_at IS NULL)
        OR (status='IN_FLIGHT' AND next_attempt_at IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND finished_at IS NULL AND attempt_count>0)
        OR (status IN ('DELIVERED','FAILED','CANCELED') AND next_attempt_at IS NULL AND lease_token IS NULL AND lease_until IS NULL AND finished_at IS NOT NULL))
);
CREATE INDEX due_webhook_deliveries ON webhook_deliveries(next_attempt_at) WHERE status='PENDING';
CREATE INDEX leased_webhook_deliveries ON webhook_deliveries(lease_until) WHERE status='IN_FLIGHT';
CREATE TABLE webhook_attempts (
    delivery_id uuid NOT NULL REFERENCES webhook_deliveries(id),
    attempt_number integer NOT NULL CHECK (attempt_number BETWEEN 1 AND 20),
    lease_token uuid NOT NULL,
    outcome varchar(20) NOT NULL CHECK (outcome IN ('IN_FLIGHT','SUCCEEDED','HTTP_ERROR','NETWORK_ERROR','ABANDONED','POLICY_REJECTED')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    http_status integer CHECK (http_status BETWEEN 100 AND 999),
    error varchar(64),
    PRIMARY KEY(delivery_id,attempt_number),
    UNIQUE(delivery_id,lease_token),
    CHECK ((outcome='IN_FLIGHT')=(finished_at IS NULL))
);

CREATE FUNCTION guard_webhook_subscription() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT OLD.active OR
       ROW(OLD.id,OLD.idempotency_key,OLD.account_id,OLD.url,OLD.signing_secret,OLD.created_at) IS DISTINCT FROM
       ROW(NEW.id,NEW.idempotency_key,NEW.account_id,NEW.url,NEW.signing_secret,NEW.created_at) THEN
        RAISE EXCEPTION 'subscription identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_webhook_subscription BEFORE UPDATE ON webhook_subscriptions
    FOR EACH ROW EXECUTE FUNCTION guard_webhook_subscription();
CREATE FUNCTION reject_webhook_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'webhook audit records are immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_webhook_events BEFORE UPDATE OR DELETE ON webhook_events
    FOR EACH ROW EXECUTE FUNCTION reject_webhook_mutation();
CREATE TRIGGER no_webhook_subscription_delete BEFORE DELETE ON webhook_subscriptions
    FOR EACH ROW EXECUTE FUNCTION reject_webhook_mutation();

CREATE FUNCTION webhook_iso(t timestamptz) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT to_char(t AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
$$;
CREATE FUNCTION capture_terminal_webhook() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE eid uuid:=gen_random_uuid(); merchant varchar(100); kind text; event_type text; happened timestamptz; data jsonb; envelope text;
BEGIN
    happened:=COALESCE(NEW.completed_at,NEW.rejected_at);
    IF TG_TABLE_NAME='payments' THEN
        merchant:=NEW.destination_account;kind:='PAYMENT';event_type:='payment.'||lower(NEW.status);
        data:=jsonb_build_object('id',NEW.id,'sourceAccount',NEW.source_account,'destinationAccount',NEW.destination_account,
            'amountMinor',NEW.amount_minor,'currency',NEW.currency,'status',NEW.status,'createdAt',webhook_iso(NEW.created_at),
            'completedAt',webhook_iso(NEW.completed_at),'rejectedAt',webhook_iso(NEW.rejected_at),'rejectionReason',NEW.rejection_reason);
    ELSE
        SELECT destination_account INTO STRICT merchant FROM payments WHERE id=NEW.payment_id;
        kind:='REFUND';event_type:='refund.'||lower(NEW.status);
        data:=jsonb_build_object('id',NEW.id,'paymentId',NEW.payment_id,'amountMinor',NEW.amount_minor,'currency',NEW.currency,
            'status',NEW.status,'createdAt',webhook_iso(NEW.created_at),'completedAt',webhook_iso(NEW.completed_at),
            'rejectedAt',webhook_iso(NEW.rejected_at),'rejectionReason',NEW.rejection_reason);
    END IF;
    envelope:=jsonb_build_object('id',eid,'type',event_type,'accountId',merchant,'occurredAt',webhook_iso(happened),'schemaVersion',1,'data',data)::text;
    INSERT INTO webhook_events(id,event_type,resource_type,resource_id,account_id,occurred_at,payload)
        VALUES(eid,event_type,kind,NEW.id,merchant,happened,envelope);
    INSERT INTO webhook_deliveries(event_id,subscription_id)
        SELECT eid,id FROM webhook_subscriptions WHERE account_id=merchant AND active;
    RETURN NULL;
END $$;
CREATE TRIGGER payment_webhook AFTER UPDATE OF status ON payments FOR EACH ROW
    WHEN (OLD.status='PENDING' AND NEW.status IN ('COMPLETED','REJECTED')) EXECUTE FUNCTION capture_terminal_webhook();
CREATE TRIGGER refund_webhook AFTER UPDATE OF status ON refunds FOR EACH ROW
    WHEN (OLD.status='PENDING' AND NEW.status IN ('COMPLETED','REJECTED')) EXECUTE FUNCTION capture_terminal_webhook();
