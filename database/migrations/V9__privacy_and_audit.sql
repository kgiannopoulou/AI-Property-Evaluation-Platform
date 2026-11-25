-- V9: GDPR consent, secure file records, audit log and data retention (Phase 10).
--
-- consent_records  append-only history of what each user agreed to, with the policy version
-- stored_files     every uploaded photo or floor plan: owner, kind and when it must be deleted
-- audit_events     append-only security and privacy log (no emails or IP addresses, only hashes)
-- purge_expired_data()  the retention rules, run daily by the backend

-- Consent --------------------------------------------------------------------------------------
CREATE TABLE consent_records (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose         text        NOT NULL CHECK (purpose IN ('terms', 'privacy-notice', 'ai-photo-processing', 'marketing-email')),
    granted         boolean     NOT NULL,
    policy_version  text        NOT NULL CHECK (length(policy_version) BETWEEN 1 AND 40),
    source          text        NOT NULL CHECK (source IN ('registration', 'settings', 'prompt')),
    recorded_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX consent_records_user_idx ON consent_records (user_id, purpose, recorded_at DESC);

-- The latest decision per user and purpose.
CREATE VIEW current_consents AS
SELECT DISTINCT ON (user_id, purpose) user_id, purpose, granted, policy_version, source, recorded_at
FROM consent_records
ORDER BY user_id, purpose, recorded_at DESC, id;

CREATE FUNCTION consent_records_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'consent_records is append-only: record a new decision instead';
END $$;
CREATE TRIGGER consent_records_no_update BEFORE UPDATE ON consent_records
    FOR EACH ROW EXECUTE FUNCTION consent_records_append_only();

-- Uploaded files (photos, floor plans) ---------------------------------------------------------
-- Files are private: the backend serves one only to its owner account (or that account's team).
CREATE TABLE stored_files (
    storage_key     text        PRIMARY KEY CHECK (length(storage_key) BETWEEN 1 AND 300),
    owner_user_id   uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,  -- the paying account
    uploaded_by     uuid        REFERENCES users (id) ON DELETE SET NULL,
    property_ref    text        NOT NULL CHECK (length(property_ref) BETWEEN 1 AND 100),
    kind            text        NOT NULL CHECK (kind IN ('inspection-photo', 'floor-plan')),
    content_type    text        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    size_bytes      integer     NOT NULL CHECK (size_bytes > 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    delete_after    timestamptz NOT NULL,
    CHECK (delete_after > created_at)
);
CREATE INDEX stored_files_owner_idx ON stored_files (owner_user_id);
CREATE INDEX stored_files_expiry_idx ON stored_files (delete_after);

-- Audit log ------------------------------------------------------------------------------------
CREATE TABLE audit_events (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_user_id  uuid        REFERENCES users (id) ON DELETE SET NULL,   -- erased users leave anonymous events
    actor_kind     text        NOT NULL CHECK (actor_kind IN ('user', 'api-key', 'system', 'anonymous')),
    action         text        NOT NULL CHECK (action ~ '^[a-z]+(\.[a-z-]+)+$'),
    outcome        text        NOT NULL CHECK (outcome IN ('success', 'denied', 'failure')),
    target         text        CHECK (length(target) <= 300),
    ip_hash        char(64),                                                -- salted SHA-256, never the address
    details        jsonb       NOT NULL DEFAULT '{}'
);
CREATE INDEX audit_events_actor_idx ON audit_events (actor_user_id, occurred_at DESC);
CREATE INDEX audit_events_time_idx ON audit_events (occurred_at);
CREATE INDEX audit_events_action_idx ON audit_events (action, occurred_at DESC);

-- Append-only: the only update allowed is the foreign key clearing the actor when an account is
-- erased; rows are deleted only by the retention purge.
CREATE FUNCTION audit_events_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.actor_user_id IS NOT NULL AND NEW.actor_user_id IS NULL
           AND (NEW.id, NEW.occurred_at, NEW.actor_kind, NEW.action, NEW.outcome, NEW.target, NEW.ip_hash, NEW.details)
               IS NOT DISTINCT FROM
               (OLD.id, OLD.occurred_at, OLD.actor_kind, OLD.action, OLD.outcome, OLD.target, OLD.ip_hash, OLD.details) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'audit_events is append-only';
    END IF;
    IF coalesce(current_setting('app.retention_purge', true), '') <> 'on' THEN
        RAISE EXCEPTION 'audit events are deleted only by the retention purge';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER audit_events_append_only BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_guard();

-- Data retention -------------------------------------------------------------------------------
-- Deletes what is no longer needed and returns how many rows went per category. Uploaded files
-- are removed by the backend first (it deletes the bytes, then the stored_files row).
CREATE FUNCTION purge_expired_data(
    p_now                 timestamptz,
    p_audit_days          integer DEFAULT 365,
    p_usage_months        integer DEFAULT 24,
    p_billing_event_days  integer DEFAULT 90,
    p_enquiry_months      integer DEFAULT 24,
    p_revoked_key_days    integer DEFAULT 90
) RETURNS TABLE (category text, deleted bigint) LANGUAGE plpgsql AS $$
DECLARE n bigint;
BEGIN
    DELETE FROM user_sessions WHERE expires_at <= p_now;
    GET DIAGNOSTICS n = ROW_COUNT; category := 'expired-sessions'; deleted := n; RETURN NEXT;

    DELETE FROM api_keys WHERE revoked_at IS NOT NULL AND revoked_at < p_now - make_interval(days => p_revoked_key_days);
    GET DIAGNOSTICS n = ROW_COUNT; category := 'revoked-api-keys'; deleted := n; RETURN NEXT;

    DELETE FROM usage_events WHERE created_at < p_now - make_interval(months => p_usage_months);
    GET DIAGNOSTICS n = ROW_COUNT; category := 'usage-events'; deleted := n; RETURN NEXT;

    DELETE FROM billing_events WHERE received_at < p_now - make_interval(days => p_billing_event_days);
    GET DIAGNOSTICS n = ROW_COUNT; category := 'billing-events'; deleted := n; RETURN NEXT;

    DELETE FROM business_enquiries WHERE created_at < p_now - make_interval(months => p_enquiry_months);
    GET DIAGNOSTICS n = ROW_COUNT; category := 'business-enquiries'; deleted := n; RETURN NEXT;

    PERFORM set_config('app.retention_purge', 'on', true);
    DELETE FROM audit_events WHERE occurred_at < p_now - make_interval(days => p_audit_days);
    GET DIAGNOSTICS n = ROW_COUNT; category := 'audit-events'; deleted := n; RETURN NEXT;
    PERFORM set_config('app.retention_purge', 'off', true);
END $$;
