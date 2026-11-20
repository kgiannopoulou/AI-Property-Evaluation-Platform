-- V1: initial schema for PostgreSQL + PostGIS.
-- Mirrors the frontend domain types in frontend/properties/types.ts and
-- frontend/analysis/types.ts so moving from browser storage to the API in
-- Phase 4 is a transport change, not a data-model change.
-- Applied by Flyway from the Spring Boot backend (Phase 4) or manually with psql.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid()

-- Plans --------------------------------------------------------------------
CREATE TABLE plans (
    id                   text PRIMARY KEY CHECK (id IN ('free', 'premium', 'professional', 'business')),
    name                 text        NOT NULL,
    monthly_price_cents  integer     NOT NULL CHECK (monthly_price_cents >= 0),
    analyses_per_month   integer     CHECK (analyses_per_month IS NULL OR analyses_per_month > 0) -- NULL = unlimited
);

INSERT INTO plans (id, name, monthly_price_cents, analyses_per_month) VALUES
    ('free', 'Free', 0, 3),
    ('premium', 'Premium', 900, 25),
    ('professional', 'Professional', 2900, 150),
    ('business', 'Business', 9900, NULL);

-- Users --------------------------------------------------------------------
CREATE TABLE users (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email          text        NOT NULL,
    password_hash  text        NOT NULL,
    full_name      text        NOT NULL,
    role           text        NOT NULL DEFAULT 'buyer' CHECK (role IN ('buyer', 'investor', 'agent', 'valuer')),
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));

CREATE TABLE user_preferences (
    user_id             uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    mortgage_rate_pct   numeric(5, 2) NOT NULL DEFAULT 3.90 CHECK (mortgage_rate_pct BETWEEN 0 AND 15),
    loan_to_value_pct   numeric(5, 2) NOT NULL DEFAULT 80   CHECK (loan_to_value_pct BETWEEN 0 AND 100),
    mortgage_years      smallint      NOT NULL DEFAULT 25   CHECK (mortgage_years BETWEEN 1 AND 40),
    email_reports       boolean       NOT NULL DEFAULT true,
    price_alerts        boolean       NOT NULL DEFAULT false,
    product_news        boolean       NOT NULL DEFAULT false
);

-- Subscriptions --------------------------------------------------------------
CREATE TABLE subscriptions (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_id               text        NOT NULL REFERENCES plans (id),
    status                text        NOT NULL CHECK (status IN ('active', 'trialing', 'past_due', 'canceled')),
    billing_period        text        NOT NULL DEFAULT 'monthly' CHECK (billing_period IN ('monthly', 'annual')),
    provider              text,                       -- e.g. 'stripe'
    provider_subscription text,                       -- provider's id; card data is never stored here
    current_period_end    timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX subscriptions_one_active_per_user ON subscriptions (user_id) WHERE status IN ('active', 'trialing', 'past_due');

-- Reference prices per area (replaces the illustrative frontend table) ---------
CREATE TABLE districts (
    id              serial PRIMARY KEY,
    name            text          NOT NULL UNIQUE,
    boundary        geography(MultiPolygon, 4326),
    sale_per_sqm    numeric(10, 2),
    rent_per_sqm    numeric(8, 2),
    location_score  smallint      CHECK (location_score BETWEEN 0 AND 100),
    source          text,                             -- where the reference values came from
    updated_at      timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX districts_boundary_gix ON districts USING gist (boundary);

-- Properties -----------------------------------------------------------------
CREATE TABLE properties (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title           text          NOT NULL,
    address         text,
    district_id     integer       REFERENCES districts (id),
    location        geography(Point, 4326),
    property_type   text          NOT NULL CHECK (property_type IN ('apartment', 'maisonette', 'detached-house', 'studio', 'penthouse')),
    size_sqm        numeric(8, 2) NOT NULL CHECK (size_sqm BETWEEN 15 AND 2000),
    bedrooms        smallint      NOT NULL CHECK (bedrooms BETWEEN 0 AND 20),
    bathrooms       smallint      NOT NULL CHECK (bathrooms BETWEEN 0 AND 10),
    floor           smallint      NOT NULL CHECK (floor BETWEEN -2 AND 60),
    year_built      smallint      NOT NULL CHECK (year_built >= 1850),
    renovated_year  smallint      CHECK (renovated_year IS NULL OR renovated_year >= year_built),
    condition       text          NOT NULL CHECK (condition IN ('needs-renovation', 'fair', 'good', 'excellent')),
    energy_class    text          NOT NULL CHECK (energy_class IN ('A+', 'A', 'B+', 'B', 'C', 'D', 'E', 'F', 'G')),
    heating         text          NOT NULL CHECK (heating IN ('central-oil', 'autonomous-gas', 'heat-pump', 'electric', 'none')),
    parking         boolean       NOT NULL DEFAULT false,
    storage         boolean       NOT NULL DEFAULT false,
    elevator        boolean       NOT NULL DEFAULT false,
    balcony_sqm     numeric(7, 2) NOT NULL DEFAULT 0 CHECK (balcony_sqm >= 0),
    asking_price    numeric(12, 2) NOT NULL CHECK (asking_price > 0),
    notes           text,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX properties_owner_idx ON properties (owner_id, created_at DESC);
CREATE INDEX properties_location_gix ON properties USING gist (location);

-- Photos and documents live in object storage; only metadata is stored here.
CREATE TABLE property_files (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id   uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    kind          text        NOT NULL CHECK (kind IN ('photo', 'document')),
    storage_key   text        NOT NULL UNIQUE,
    file_name     text        NOT NULL,
    content_type  text        NOT NULL,
    size_bytes    bigint      NOT NULL CHECK (size_bytes > 0),
    room          text,
    uploaded_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX property_files_property_idx ON property_files (property_id);

-- Analyses -------------------------------------------------------------------
-- Headline numbers are columns (for lists, sorting and comparison); the full
-- result keeps its nested shape in JSONB.
CREATE TABLE analyses (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id      uuid          NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    engine           text          NOT NULL,          -- e.g. 'sample-v1', 'valuation-v1'
    estimate_low     numeric(12, 2) NOT NULL,
    estimate_mid     numeric(12, 2) NOT NULL,
    estimate_high    numeric(12, 2) NOT NULL,
    verdict          text          NOT NULL CHECK (verdict IN ('below-market', 'fair', 'above-market')),
    delta_pct        numeric(6, 2) NOT NULL,
    confidence       smallint      NOT NULL CHECK (confidence BETWEEN 0 AND 100),
    overall_score    smallint      NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
    result           jsonb         NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    CHECK (estimate_low <= estimate_mid AND estimate_mid <= estimate_high)
);
CREATE INDEX analyses_property_latest_idx ON analyses (property_id, created_at DESC);

-- Saved comparisons ------------------------------------------------------------
CREATE TABLE comparisons (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE comparison_properties (
    comparison_id  uuid     NOT NULL REFERENCES comparisons (id) ON DELETE CASCADE,
    property_id    uuid     NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    position       smallint NOT NULL CHECK (position BETWEEN 1 AND 4),
    PRIMARY KEY (comparison_id, property_id),
    UNIQUE (comparison_id, position)
);
