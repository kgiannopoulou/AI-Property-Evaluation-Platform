-- V2: full evaluation data model.
--
-- Extends V1 (users, plans, subscriptions, districts, properties, analyses,
-- comparisons) with the tables every evaluation module needs:
--
--   users (profile)      accounts and profiles
--   properties           property details (now linked to a building)
--   property_images      uploaded photographs
--   property_features    rooms, facilities and amenities
--   equipment            appliances and furniture
--   buildings            building condition and shared facilities
--   inspections          visual inspection findings
--   valuations           estimated value and confidence
--   comparables          similar property records (+ valuation_comparables)
--   location_data        nearby facilities and geographical information
--   expenses             purchase, rental and ownership costs
--   renovations          repair items and cost estimates
--   investments          rental income, yield and ROI
--   documents            uploaded documents and review results
--   saved_properties     a user's saved properties
--   subscriptions        plans and payment status
--   reports              generated evaluation reports
--
-- Conventions: uuid keys, timestamptz, money as numeric(12,2) in EUR unless a
-- currency column says otherwise, enumerations as text + CHECK (readable in SQL
-- and simple to extend in a later migration), geography(…, 4326) for anything
-- measured in metres. Binary files live in object storage; tables keep metadata.

-- Indexes V1 left out: foreign keys that are looked up from the other side.
CREATE INDEX comparisons_owner_idx ON comparisons (owner_id, created_at DESC);
CREATE INDEX comparison_properties_property_idx ON comparison_properties (property_id);

-- Shared trigger: keep updated_at current --------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER properties_set_updated_at BEFORE UPDATE ON properties
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Users: accounts and profiles ----------------------------------------------------
ALTER TABLE users
    ADD COLUMN phone            text,
    ADD COLUMN company          text,                 -- agency or firm, for agents and valuers
    ADD COLUMN bio              text,
    ADD COLUMN avatar_key       text,                 -- object-storage key, not a public URL
    ADD COLUMN locale           text        NOT NULL DEFAULT 'en' CHECK (locale IN ('en', 'el')),
    ADD COLUMN email_verified_at timestamptz,
    ADD COLUMN last_login_at    timestamptz,
    ADD COLUMN updated_at       timestamptz NOT NULL DEFAULT now();

CREATE TRIGGER users_set_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Subscriptions: plans and payment status -------------------------------------------
ALTER TABLE subscriptions
    ADD COLUMN payment_status        text        NOT NULL DEFAULT 'none'
        CHECK (payment_status IN ('none', 'paid', 'pending', 'failed', 'refunded')),
    ADD COLUMN cancel_at_period_end  boolean     NOT NULL DEFAULT false,
    ADD COLUMN current_period_start  timestamptz,
    ADD COLUMN last_payment_at       timestamptz,
    ADD COLUMN last_payment_cents    integer     CHECK (last_payment_cents IS NULL OR last_payment_cents >= 0),
    ADD COLUMN canceled_at           timestamptz,
    ADD COLUMN updated_at            timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT subscriptions_period_order
        CHECK (current_period_start IS NULL OR current_period_end IS NULL OR current_period_start < current_period_end);

CREATE INDEX subscriptions_user_idx ON subscriptions (user_id, created_at DESC);
CREATE UNIQUE INDEX subscriptions_provider_uq ON subscriptions (provider, provider_subscription)
    WHERE provider_subscription IS NOT NULL;

CREATE TRIGGER subscriptions_set_updated_at BEFORE UPDATE ON subscriptions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Buildings: condition and shared facilities ------------------------------------------
-- Several apartments can share one building record, so building condition is
-- entered (or inspected) once.
CREATE TABLE buildings (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    address                 text,
    location                geography(Point, 4326),
    year_built              smallint      CHECK (year_built IS NULL OR year_built >= 1850),
    floors_above_ground     smallint      CHECK (floors_above_ground IS NULL OR floors_above_ground BETWEEN 1 AND 100),
    units_total             smallint      CHECK (units_total IS NULL OR units_total BETWEEN 1 AND 2000),
    structure_type          text          CHECK (structure_type IN ('reinforced-concrete', 'masonry', 'steel', 'timber', 'mixed')),
    seismic_upgrade_year    smallint      CHECK (seismic_upgrade_year IS NULL OR seismic_upgrade_year >= 1950),
    structural_condition    text          CHECK (structural_condition IN ('poor', 'fair', 'good', 'excellent')),
    facade_condition        text          CHECK (facade_condition IN ('poor', 'fair', 'good', 'excellent')),
    roof_condition          text          CHECK (roof_condition IN ('poor', 'fair', 'good', 'excellent')),
    common_areas_condition  text          CHECK (common_areas_condition IN ('poor', 'fair', 'good', 'excellent')),
    elevator_count          smallint      NOT NULL DEFAULT 0 CHECK (elevator_count BETWEEN 0 AND 20),
    shared_facilities       text[]        NOT NULL DEFAULT '{}',  -- e.g. {garden,pool,garage,storage-rooms,solar-water,concierge}
    monthly_common_charges  numeric(8, 2) CHECK (monthly_common_charges IS NULL OR monthly_common_charges >= 0),
    has_building_permit     boolean,
    notes                   text,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX buildings_location_gix ON buildings USING gist (location);
CREATE INDEX buildings_shared_facilities_gin ON buildings USING gin (shared_facilities);

CREATE TRIGGER buildings_set_updated_at BEFORE UPDATE ON buildings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE properties
    ADD COLUMN building_id uuid REFERENCES buildings (id) ON DELETE SET NULL;
CREATE INDEX properties_building_idx ON properties (building_id);
CREATE INDEX properties_district_idx ON properties (district_id);

-- Property images: uploaded photographs ---------------------------------------------
CREATE TABLE property_images (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id   uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    uploaded_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    storage_key   text        NOT NULL UNIQUE,
    file_name     text        NOT NULL,
    content_type  text        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp', 'image/heic')),
    size_bytes    bigint      NOT NULL CHECK (size_bytes BETWEEN 1 AND 52428800), -- 50 MB
    width_px      integer     CHECK (width_px IS NULL OR width_px > 0),
    height_px     integer     CHECK (height_px IS NULL OR height_px > 0),
    room          text,                               -- e.g. kitchen, bathroom, living-room, facade
    caption       text,
    position      smallint    NOT NULL DEFAULT 0 CHECK (position >= 0),
    is_cover      boolean     NOT NULL DEFAULT false,
    analysed_at   timestamptz,                        -- set when the AI Home Inspector has processed it
    uploaded_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX property_images_property_idx ON property_images (property_id, position);
CREATE UNIQUE INDEX property_images_one_cover ON property_images (property_id) WHERE is_cover;

-- Documents: uploads and review results ------------------------------------------------
CREATE TABLE documents (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id       uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    uploaded_by       uuid        REFERENCES users (id) ON DELETE SET NULL,
    document_type     text        NOT NULL DEFAULT 'other' CHECK (document_type IN (
                          'title-deed', 'energy-certificate', 'building-permit', 'floor-plan',
                          'cadastral-extract', 'tax-certificate', 'utility-bill', 'lease',
                          'purchase-contract', 'common-charges-statement', 'other')),
    storage_key       text        NOT NULL UNIQUE,
    file_name         text        NOT NULL,
    content_type      text        NOT NULL,
    size_bytes        bigint      NOT NULL CHECK (size_bytes BETWEEN 1 AND 52428800),
    review_status     text        NOT NULL DEFAULT 'pending'
                          CHECK (review_status IN ('pending', 'in-review', 'passed', 'issues-found', 'failed')),
    review_engine     text,                           -- which checker produced the result
    review_summary    text,
    review_findings   jsonb       NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(review_findings) = 'array'),
    reviewed_at       timestamptz,
    expires_on        date,                           -- e.g. energy certificates
    uploaded_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (review_status IN ('pending', 'in-review') OR reviewed_at IS NOT NULL)
);
CREATE INDEX documents_property_idx ON documents (property_id, uploaded_at DESC);
CREATE INDEX documents_review_queue_idx ON documents (uploaded_at) WHERE review_status IN ('pending', 'in-review');

-- V1 kept photos and documents in one table. Move existing rows into the
-- dedicated tables, then retire it.
INSERT INTO property_images (id, property_id, storage_key, file_name, content_type, size_bytes, room, uploaded_at)
SELECT id, property_id, storage_key, file_name,
       CASE WHEN content_type IN ('image/jpeg', 'image/png', 'image/webp', 'image/heic') THEN content_type ELSE 'image/jpeg' END,
       LEAST(size_bytes, 52428800), room, uploaded_at
FROM property_files WHERE kind = 'photo';

INSERT INTO documents (id, property_id, storage_key, file_name, content_type, size_bytes, uploaded_at)
SELECT id, property_id, storage_key, file_name, content_type, LEAST(size_bytes, 52428800), uploaded_at
FROM property_files WHERE kind = 'document';

DROP TABLE property_files;

-- Property features: rooms, facilities and amenities -------------------------------------
CREATE TABLE property_features (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id   uuid          NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    category      text          NOT NULL CHECK (category IN ('room', 'facility', 'amenity')),
    name          text          NOT NULL,             -- e.g. bedroom, kitchen / fireplace, alarm / sea-view, garden
    quantity      smallint      NOT NULL DEFAULT 1 CHECK (quantity BETWEEN 1 AND 100),
    area_sqm      numeric(7, 2) CHECK (area_sqm IS NULL OR area_sqm > 0),
    condition     text          CHECK (condition IN ('poor', 'fair', 'good', 'excellent')),
    details       jsonb         NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(details) = 'object'),
    UNIQUE (property_id, category, name)
);
CREATE INDEX property_features_lookup_idx ON property_features (category, name);

-- Equipment: appliances and furniture ---------------------------------------------------
CREATE TABLE equipment (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id         uuid           NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    source_image_id     uuid           REFERENCES property_images (id) ON DELETE SET NULL, -- photo it was detected in
    category            text           NOT NULL CHECK (category IN ('appliance', 'furniture', 'fixture', 'heating-cooling', 'electronics', 'other')),
    name                text           NOT NULL,     -- e.g. refrigerator, air-conditioner, sofa
    brand               text,
    model               text,
    quantity            smallint       NOT NULL DEFAULT 1 CHECK (quantity BETWEEN 1 AND 100),
    room                text,
    condition           text           CHECK (condition IN ('poor', 'fair', 'good', 'excellent')),
    approx_age_years    smallint       CHECK (approx_age_years IS NULL OR approx_age_years BETWEEN 0 AND 100),
    energy_label        text           CHECK (energy_label IN ('A', 'B', 'C', 'D', 'E', 'F', 'G')),
    included_in_sale    boolean        NOT NULL DEFAULT true,
    estimated_value     numeric(10, 2) CHECK (estimated_value IS NULL OR estimated_value >= 0),
    detection_confidence smallint      CHECK (detection_confidence IS NULL OR detection_confidence BETWEEN 0 AND 100),
    created_at          timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX equipment_property_idx ON equipment (property_id);

-- Inspections: visual inspection findings ------------------------------------------------
-- One row per finding, whether it came from the AI Home Inspector or a person.
CREATE TABLE inspections (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id     uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    building_id     uuid        REFERENCES buildings (id) ON DELETE CASCADE,       -- set for building-level findings
    image_id        uuid        REFERENCES property_images (id) ON DELETE SET NULL,
    analysis_id     uuid        REFERENCES analyses (id) ON DELETE SET NULL,
    source          text        NOT NULL CHECK (source IN ('ai-vision', 'manual', 'professional-survey')),
    engine          text,                             -- model/version for ai-vision findings
    room            text,
    category        text        NOT NULL CHECK (category IN (
                        'damp', 'mould', 'cracks', 'structural', 'roof', 'windows', 'electrical',
                        'plumbing', 'heating', 'finishes', 'flooring', 'insulation', 'safety', 'other')),
    severity        text        NOT NULL CHECK (severity IN ('info', 'minor', 'moderate', 'major', 'critical')),
    title           text        NOT NULL,
    description     text,
    confidence      smallint    CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 100),
    region          jsonb,                            -- bounding box on the image: {"x":..,"y":..,"w":..,"h":..} in 0..1
    status          text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'confirmed', 'dismissed', 'resolved')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (source <> 'ai-vision' OR confidence IS NOT NULL),
    CHECK (region IS NULL OR jsonb_typeof(region) = 'object')
);
CREATE INDEX inspections_property_idx ON inspections (property_id, severity);
CREATE INDEX inspections_image_idx ON inspections (image_id) WHERE image_id IS NOT NULL;
CREATE INDEX inspections_building_idx ON inspections (building_id) WHERE building_id IS NOT NULL;

-- Valuations: estimated value and confidence -----------------------------------------------
CREATE TABLE valuations (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id      uuid           NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    analysis_id      uuid           REFERENCES analyses (id) ON DELETE SET NULL,
    method           text           NOT NULL CHECK (method IN ('comparables', 'hedonic', 'income', 'ai-ensemble', 'sample', 'manual')),
    engine           text           NOT NULL,         -- e.g. 'sample-v1', 'comparables-v1'
    currency         char(3)        NOT NULL DEFAULT 'EUR',
    estimate_low     numeric(12, 2) NOT NULL CHECK (estimate_low > 0),
    estimate_mid     numeric(12, 2) NOT NULL,
    estimate_high    numeric(12, 2) NOT NULL,
    price_per_sqm    numeric(10, 2) CHECK (price_per_sqm IS NULL OR price_per_sqm > 0),
    confidence       smallint       NOT NULL CHECK (confidence BETWEEN 0 AND 100),
    asking_price     numeric(12, 2) CHECK (asking_price IS NULL OR asking_price > 0), -- asking price at valuation time
    verdict          text           CHECK (verdict IN ('below-market', 'fair', 'above-market')),
    factors          jsonb          NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(factors) = 'array'), -- what moved the estimate
    explanation      text,
    valued_at        timestamptz    NOT NULL DEFAULT now(),
    CHECK (estimate_low <= estimate_mid AND estimate_mid <= estimate_high)
);
CREATE INDEX valuations_property_latest_idx ON valuations (property_id, valued_at DESC);

-- Comparables: similar property records -----------------------------------------------------
-- Market records (listings, sales, rentals) that valuations are compared against.
CREATE TABLE comparables (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source           text           NOT NULL,         -- data provider or 'manual'
    source_ref       text,                            -- provider's id, for de-duplication
    listing_type     text           NOT NULL CHECK (listing_type IN ('sale-listing', 'sold', 'rent-listing', 'rented')),
    address          text,
    district_id      integer        REFERENCES districts (id),
    location         geography(Point, 4326) NOT NULL,
    property_type    text           NOT NULL CHECK (property_type IN ('apartment', 'maisonette', 'detached-house', 'studio', 'penthouse')),
    size_sqm         numeric(8, 2)  NOT NULL CHECK (size_sqm BETWEEN 10 AND 5000),
    bedrooms         smallint       CHECK (bedrooms IS NULL OR bedrooms BETWEEN 0 AND 20),
    floor            smallint       CHECK (floor IS NULL OR floor BETWEEN -2 AND 60),
    year_built       smallint       CHECK (year_built IS NULL OR year_built >= 1850),
    condition        text           CHECK (condition IN ('needs-renovation', 'fair', 'good', 'excellent')),
    energy_class     text           CHECK (energy_class IN ('A+', 'A', 'B+', 'B', 'C', 'D', 'E', 'F', 'G')),
    price            numeric(12, 2) NOT NULL CHECK (price > 0),  -- sale price, or monthly rent for rent-* types
    price_per_sqm    numeric(10, 2) GENERATED ALWAYS AS (round(price / size_sqm, 2)) STORED,
    observed_on      date           NOT NULL,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    UNIQUE (source, source_ref)
);
CREATE INDEX comparables_location_gix ON comparables USING gist (location);
CREATE INDEX comparables_district_idx ON comparables (district_id);
CREATE INDEX comparables_search_idx ON comparables (listing_type, property_type, observed_on DESC);

-- Which comparables a valuation used, and how much each one counted.
CREATE TABLE valuation_comparables (
    valuation_id     uuid           NOT NULL REFERENCES valuations (id) ON DELETE CASCADE,
    comparable_id    uuid           NOT NULL REFERENCES comparables (id) ON DELETE RESTRICT,
    distance_m       numeric(9, 1)  NOT NULL CHECK (distance_m >= 0),
    similarity       numeric(4, 3)  NOT NULL CHECK (similarity BETWEEN 0 AND 1),
    adjusted_price   numeric(12, 2) CHECK (adjusted_price IS NULL OR adjusted_price > 0),
    weight           numeric(4, 3)  NOT NULL CHECK (weight BETWEEN 0 AND 1),
    PRIMARY KEY (valuation_id, comparable_id)
);
CREATE INDEX valuation_comparables_comparable_idx ON valuation_comparables (comparable_id);

-- Location data: nearby facilities and geographical information ----------------------------
-- Points of interest imported from a maps/open-data provider. Distances are
-- computed in metres with PostGIS geography (see V3 for the search functions).
CREATE TABLE location_data (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category      text        NOT NULL CHECK (category IN (
                      'pharmacy', 'hospital', 'clinic', 'school', 'university', 'kindergarten',
                      'metro', 'train', 'tram', 'bus-stop', 'supermarket', 'bakery', 'park',
                      'playground', 'gym', 'bank', 'post-office', 'police', 'restaurant', 'cafe', 'parking', 'other')),
    name          text        NOT NULL,
    address       text,
    location      geography(Point, 4326) NOT NULL,
    district_id   integer     REFERENCES districts (id),
    source        text        NOT NULL,               -- e.g. 'openstreetmap', 'google-places', 'manual'
    source_ref    text,
    attributes    jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(attributes) = 'object'), -- opening hours, lines served…
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source, source_ref)
);
CREATE INDEX location_data_location_gix ON location_data USING gist (location);
CREATE INDEX location_data_category_idx ON location_data (category);
CREATE INDEX location_data_district_idx ON location_data (district_id);

CREATE TRIGGER location_data_set_updated_at BEFORE UPDATE ON location_data
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Expenses: purchase, rental and ownership costs -----------------------------------------------
CREATE TABLE expenses (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id   uuid           NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    stage         text           NOT NULL CHECK (stage IN ('purchase', 'rental', 'ownership')),
    category      text           NOT NULL CHECK (category IN (
                      'transfer-tax', 'notary', 'lawyer', 'agent-fee', 'land-registry', 'engineer',
                      'property-tax', 'insurance', 'common-charges', 'utilities', 'maintenance',
                      'management-fee', 'vacancy', 'mortgage-interest', 'income-tax', 'other')),
    description   text,
    amount        numeric(12, 2) NOT NULL CHECK (amount >= 0),
    frequency     text           NOT NULL CHECK (frequency IN ('one-off', 'monthly', 'annual')),
    annual_amount numeric(12, 2) GENERATED ALWAYS AS (
                      CASE frequency WHEN 'monthly' THEN amount * 12 WHEN 'annual' THEN amount ELSE NULL END) STORED,
    is_estimate   boolean        NOT NULL DEFAULT true,
    incurred_on   date,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    CHECK (stage <> 'purchase' OR frequency = 'one-off')
);
CREATE INDEX expenses_property_idx ON expenses (property_id, stage);

-- Renovations: repair items and cost estimates -------------------------------------------------
CREATE TABLE renovations (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id      uuid           NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    inspection_id    uuid           REFERENCES inspections (id) ON DELETE SET NULL, -- finding that triggered it
    room             text,
    item             text           NOT NULL,         -- e.g. 'Replace bathroom tiles'
    category         text           NOT NULL CHECK (category IN (
                         'structural', 'roof', 'windows', 'electrical', 'plumbing', 'heating-cooling',
                         'insulation', 'kitchen', 'bathroom', 'flooring', 'painting', 'other')),
    priority         text           NOT NULL CHECK (priority IN ('urgent', 'recommended', 'optional')),
    cost_low         numeric(12, 2) NOT NULL CHECK (cost_low >= 0),
    cost_high        numeric(12, 2) NOT NULL,
    currency         char(3)        NOT NULL DEFAULT 'EUR',
    value_uplift     numeric(12, 2),                  -- expected effect on value; may be negative
    duration_days    smallint       CHECK (duration_days IS NULL OR duration_days BETWEEN 1 AND 730),
    status           text           NOT NULL DEFAULT 'planned' CHECK (status IN ('planned', 'quoted', 'in-progress', 'done', 'skipped')),
    notes            text,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    CHECK (cost_low <= cost_high)
);
CREATE INDEX renovations_property_idx ON renovations (property_id, priority);

-- Investments: rental income, yield and ROI ------------------------------------------------------
-- One row per scenario. Yields are generated so they can never drift from
-- their inputs; financed ROI and cash flow are written by the backend calculator.
CREATE TABLE investments (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id              uuid           NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    created_by               uuid           REFERENCES users (id) ON DELETE SET NULL,
    scenario_name            text           NOT NULL DEFAULT 'Base case',
    purchase_price           numeric(12, 2) NOT NULL CHECK (purchase_price > 0),
    purchase_costs           numeric(12, 2) NOT NULL DEFAULT 0 CHECK (purchase_costs >= 0),
    renovation_costs         numeric(12, 2) NOT NULL DEFAULT 0 CHECK (renovation_costs >= 0),
    down_payment             numeric(12, 2) CHECK (down_payment IS NULL OR down_payment >= 0),
    loan_amount              numeric(12, 2) NOT NULL DEFAULT 0 CHECK (loan_amount >= 0),
    interest_rate_pct        numeric(5, 2)  CHECK (interest_rate_pct IS NULL OR interest_rate_pct BETWEEN 0 AND 25),
    loan_years               smallint       CHECK (loan_years IS NULL OR loan_years BETWEEN 1 AND 40),
    monthly_rent             numeric(10, 2) NOT NULL CHECK (monthly_rent >= 0),
    occupancy_pct            numeric(5, 2)  NOT NULL DEFAULT 95 CHECK (occupancy_pct BETWEEN 0 AND 100),
    annual_operating_costs   numeric(12, 2) NOT NULL DEFAULT 0 CHECK (annual_operating_costs >= 0),
    annual_appreciation_pct  numeric(5, 2)  CHECK (annual_appreciation_pct IS NULL OR annual_appreciation_pct BETWEEN -20 AND 30),
    holding_years            smallint       CHECK (holding_years IS NULL OR holding_years BETWEEN 1 AND 50),
    total_investment         numeric(12, 2) GENERATED ALWAYS AS (purchase_price + purchase_costs + renovation_costs) STORED,
    gross_yield_pct          numeric(6, 2)  GENERATED ALWAYS AS (round(monthly_rent * 12 / purchase_price * 100, 2)) STORED,
    net_yield_pct            numeric(6, 2)  GENERATED ALWAYS AS (round(
                                 (monthly_rent * 12 * occupancy_pct / 100 - annual_operating_costs)
                                 / (purchase_price + purchase_costs + renovation_costs) * 100, 2)) STORED,
    monthly_cash_flow        numeric(10, 2),          -- after loan payments (backend calculator)
    roi_pct                  numeric(7, 2),           -- over holding_years (backend calculator)
    payback_years            numeric(5, 1)  CHECK (payback_years IS NULL OR payback_years > 0),
    assumptions              jsonb          NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(assumptions) = 'object'),
    created_at               timestamptz    NOT NULL DEFAULT now(),
    CHECK (loan_amount <= purchase_price + purchase_costs + renovation_costs),
    CHECK (loan_amount = 0 OR (interest_rate_pct IS NOT NULL AND loan_years IS NOT NULL))
);
CREATE INDEX investments_property_idx ON investments (property_id, created_at DESC);

-- Saved properties ----------------------------------------------------------------------------
CREATE TABLE saved_properties (
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    property_id   uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    label         text,                               -- e.g. 'Shortlist', 'Viewing booked'
    note          text,
    price_alert   boolean     NOT NULL DEFAULT false,
    saved_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, property_id)
);
CREATE INDEX saved_properties_property_idx ON saved_properties (property_id);

-- Reports: generated evaluation reports ---------------------------------------------------------
CREATE TABLE reports (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id     uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    requested_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    analysis_id     uuid        REFERENCES analyses (id) ON DELETE SET NULL,
    valuation_id    uuid        REFERENCES valuations (id) ON DELETE SET NULL,
    investment_id   uuid        REFERENCES investments (id) ON DELETE SET NULL,
    report_type     text        NOT NULL CHECK (report_type IN ('full-evaluation', 'valuation', 'inspection', 'investment', 'negotiation')),
    format          text        NOT NULL DEFAULT 'pdf' CHECK (format IN ('pdf', 'html')),
    language        text        NOT NULL DEFAULT 'en' CHECK (language IN ('en', 'el')),
    status          text        NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'generating', 'ready', 'failed')),
    title           text        NOT NULL,
    summary         jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(summary) = 'object'), -- headline numbers shown in lists
    storage_key     text        UNIQUE,
    size_bytes      bigint      CHECK (size_bytes IS NULL OR size_bytes > 0),
    error           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    CHECK (status <> 'ready' OR (storage_key IS NOT NULL AND completed_at IS NOT NULL)),
    CHECK (status <> 'failed' OR error IS NOT NULL)
);
CREATE INDEX reports_property_idx ON reports (property_id, created_at DESC);
CREATE INDEX reports_requested_by_idx ON reports (requested_by, created_at DESC);
