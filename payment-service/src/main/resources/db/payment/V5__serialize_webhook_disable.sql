-- Fanout and disable share a subscription lock, so disable cannot miss uncommitted deliveries.
CREATE OR REPLACE FUNCTION capture_terminal_webhook() RETURNS trigger LANGUAGE plpgsql AS $$
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
        SELECT eid,id FROM webhook_subscriptions WHERE account_id=merchant AND active ORDER BY id FOR SHARE;
    RETURN NULL;
END $$;
