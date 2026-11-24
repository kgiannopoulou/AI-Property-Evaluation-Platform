-- V8: accounts, subscriptions and payments (Phase 9, freemium).
--
-- Plans carry the pricing HYPOTHESES being tested (Free €0 / Premium €9.99 / Professional €29.99 /
-- Business custom); the backend's PlanCatalog is the source of truth and a test keeps the two in step.
-- Usage limits are enforced by the backend: one usage_events row per property analysed per account per
-- calendar month, so re-running an analysis of the same property does not count twice.

-- Plans ------------------------------------------------------------------------------------
ALTER TABLE plans
    ALTER COLUMN monthly_price_cents DROP NOT NULL,
    ADD COLUMN custom_pricing    boolean  NOT NULL DEFAULT false,
    ADD COLUMN max_inspection_photos smallint NOT NULL DEFAULT 24 CHECK (max_inspection_photos BETWEEN 1 AND 24),
    ADD COLUMN max_compare       smallint NOT NULL DEFAULT 6 CHECK (max_compare BETWEEN 2 AND 6),
    ADD COLUMN features          text[]   NOT NULL DEFAULT '{}',
    ADD CONSTRAINT plans_price_or_custom CHECK (custom_pricing OR monthly_price_cents IS NOT NULL);

UPDATE plans SET name = 'Free', monthly_price_cents = 0, analyses_per_month = 2, max_inspection_photos = 6, max_compare = 2,
       features = '{valuation,basic-inspection,financial,comparison}' WHERE id = 'free';
UPDATE plans SET name = 'Premium', monthly_price_cents = 999, analyses_per_month = 20, max_inspection_photos = 24, max_compare = 4,
       features = '{valuation,basic-inspection,financial,comparison,advanced-valuation,full-inspection,reports,location,renovation,market,building,documents,floor-plans,negotiation}'
WHERE id = 'premium';
UPDATE plans SET name = 'Professional', monthly_price_cents = 2999, analyses_per_month = 100, max_inspection_photos = 24, max_compare = 6,
       features = '{valuation,basic-inspection,financial,comparison,advanced-valuation,full-inspection,reports,location,renovation,market,building,documents,floor-plans,negotiation,investment,client-reports}'
WHERE id = 'professional';
-- Business: price and monthly analyses are set per contract (subscriptions.analyses_limit).
UPDATE plans SET name = 'Business', monthly_price_cents = NULL, custom_pricing = true, analyses_per_month = NULL,
       max_inspection_photos = 24, max_compare = 6,
       features = '{valuation,basic-inspection,financial,comparison,advanced-valuation,full-inspection,reports,location,renovation,market,building,documents,floor-plans,negotiation,investment,client-reports,teams,bulk-analysis,api-access}'
WHERE id = 'business';

-- Subscriptions: payment-provider customer and contract terms --------------------------------
ALTER TABLE subscriptions
    ADD COLUMN provider_customer text,
    ADD COLUMN analyses_limit    integer  CHECK (analyses_limit IS NULL OR analyses_limit > 0),   -- Business contract
    ADD COLUMN seats             smallint CHECK (seats IS NULL OR seats BETWEEN 1 AND 500);         -- Business contract
CREATE INDEX subscriptions_provider_customer_idx ON subscriptions (provider_customer) WHERE provider_customer IS NOT NULL;

-- Sessions: only a SHA-256 hash of the bearer token is stored ---------------------------------
CREATE TABLE user_sessions (
    token_hash    char(64)    PRIMARY KEY,
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    last_used_at  timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX user_sessions_user_idx ON user_sessions (user_id);
CREATE INDEX user_sessions_expiry_idx ON user_sessions (expires_at);

-- API keys (Business plan): shown once, stored as a hash ------------------------------------
CREATE TABLE api_keys (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name           text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    prefix         text        NOT NULL,                  -- first characters, to recognise a key in lists
    key_hash       char(64)    NOT NULL UNIQUE,
    created_at     timestamptz NOT NULL DEFAULT now(),
    last_used_at   timestamptz,
    revoked_at     timestamptz
);
CREATE INDEX api_keys_owner_idx ON api_keys (owner_user_id);

-- Teams (Business plan): members share the owner's subscription and monthly analyses ----------
CREATE TABLE organisations (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id  uuid        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    name           text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE organisation_members (
    organisation_id uuid        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,  -- one team per user
    role            text        NOT NULL DEFAULT 'member' CHECK (role IN ('admin', 'member')),
    added_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organisation_id, user_id)
);

-- Metered usage -----------------------------------------------------------------------------
CREATE TABLE usage_events (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_user_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,   -- who pays (team owner)
    actor_user_id    uuid        REFERENCES users (id) ON DELETE SET NULL,           -- who ran it
    period           date        NOT NULL CHECK (extract(day FROM period) = 1),      -- first day of the month (UTC)
    property_ref     text        NOT NULL CHECK (length(property_ref) BETWEEN 1 AND 100),
    endpoint         text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_user_id, period, property_ref)
);
CREATE INDEX usage_events_actor_idx ON usage_events (actor_user_id);

-- Payment-provider webhooks are processed once ------------------------------------------------
CREATE TABLE billing_events (
    event_id     text PRIMARY KEY,
    type         text        NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now()
);

-- Business plan enquiries ("Custom" pricing) ---------------------------------------------------
CREATE TABLE business_enquiries (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid        REFERENCES users (id) ON DELETE SET NULL,
    company           text        NOT NULL,
    contact_email     text        NOT NULL,
    seats             smallint    CHECK (seats IS NULL OR seats BETWEEN 1 AND 500),
    monthly_analyses  integer     CHECK (monthly_analyses IS NULL OR monthly_analyses > 0),
    message           text,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX business_enquiries_user_idx ON business_enquiries (user_id);
