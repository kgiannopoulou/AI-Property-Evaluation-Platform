-- Schema tests. Run after the migrations:
--   psql -v ON_ERROR_STOP=1 -f database/tests/schema_test.sql
-- Everything runs in one transaction that is rolled back, so the database is
-- left exactly as it was. Any failed ASSERT or unexpected error aborts the run.

\set ON_ERROR_STOP 1
\set QUIET 1
\o /dev/null
BEGIN;

-- Fails unless the statement raises the given SQLSTATE (e.g. 23514 check_violation).
CREATE FUNCTION pg_temp.expect_error(p_sql text, p_sqlstate text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE p_sql;
    RAISE EXCEPTION 'expected SQLSTATE % but statement succeeded: %', p_sqlstate, p_sql;
EXCEPTION WHEN OTHERS THEN
    IF SQLSTATE <> p_sqlstate THEN
        RAISE EXCEPTION 'expected SQLSTATE %, got % (%) for: %', p_sqlstate, SQLSTATE, SQLERRM, p_sql;
    END IF;
END;
$$;

-- 1. Every Phase 4 table exists ------------------------------------------------------
DO $$
DECLARE
    expected text[] := ARRAY['users', 'properties', 'property_images', 'property_features', 'equipment',
        'buildings', 'inspections', 'valuations', 'comparables', 'location_data', 'expenses',
        'renovations', 'investments', 'documents', 'saved_properties', 'subscriptions', 'reports',
        'appliance_checklist', 'ai_inspection_runs'];
    missing text[];
BEGIN
    SELECT array_agg(t) INTO missing
    FROM unnest(expected) t
    WHERE to_regclass('public.' || t) IS NULL;
    ASSERT missing IS NULL, format('missing tables: %s', missing);
    ASSERT to_regclass('public.property_files') IS NULL, 'property_files should be replaced by property_images + documents';
END $$;

-- 2. Every foreign key column used for lookups has an index --------------------------
DO $$
DECLARE
    unindexed text;
BEGIN
    SELECT string_agg(format('%s.%s', c.conrelid::regclass, a.attname), ', ') INTO unindexed
    FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
    WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
      AND array_length(c.conkey, 1) = 1
      AND a.attname NOT IN ('uploaded_by', 'created_by', 'analysis_id', 'valuation_id', 'investment_id',
                            'inspection_id', 'source_image_id', 'plan_id')  -- reverse lookups not needed
      AND NOT EXISTS (
          SELECT 1 FROM pg_index i
          WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1]);
    ASSERT unindexed IS NULL, format('foreign keys without a leading index: %s', unindexed);
END $$;

-- Fixtures ------------------------------------------------------------------------------
-- Start the searches from an empty map so existing data (e.g. the demo seed)
-- cannot change the results. Rolled back with everything else.
DELETE FROM valuation_comparables;
DELETE FROM comparables;
DELETE FROM location_data;

-- A 2 km box around Syntagma Square, Athens, and points at known offsets.
-- At this latitude 0.001° latitude ≈ 111 m and 0.001° longitude ≈ 88 m.
INSERT INTO districts (id, name, boundary, sale_per_sqm, rent_per_sqm, location_score, source)
VALUES (9001, 'Test district', ST_Multi(ST_MakeEnvelope(23.72, 37.965, 23.75, 37.985, 4326))::geography,
        4000, 15, 80, 'schema test');

INSERT INTO users (id, email, password_hash, full_name, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'Test.Buyer@example.test', 'x', 'Test Buyer', 'buyer');

INSERT INTO buildings (id, address, location, year_built, floors_above_ground, shared_facilities, monthly_common_charges)
VALUES ('00000000-0000-0000-0000-0000000000b1', 'Test building', geo_point(23.7348, 37.9755), 1975, 6,
        '{elevator,storage-rooms}', 60);

INSERT INTO properties (id, owner_id, building_id, title, location, property_type, size_sqm, bedrooms,
                        bathrooms, floor, year_built, condition, energy_class, heating, asking_price)
VALUES ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-0000000000b1', 'Test flat', geo_point(23.7348, 37.9755),
        'apartment', 80, 2, 1, 3, 1975, 'fair', 'D', 'central-oil', 250000);

INSERT INTO location_data (category, name, location, source, source_ref) VALUES
    ('pharmacy',    'Pharmacy 220 m north', geo_point(23.7348, 37.9775), 'test', 'p1'),
    ('pharmacy',    'Pharmacy 2.2 km north', geo_point(23.7348, 37.9955), 'test', 'p2'),
    ('metro',       'Metro 530 m east',     geo_point(23.7408, 37.9755), 'test', 'm1'),
    ('school',      'School 450 m south',   geo_point(23.7348, 37.9715), 'test', 's1'),
    ('supermarket', 'Supermarket 90 m',     geo_point(23.7358, 37.9755), 'test', 'u1');

-- 3. District is assigned from the location -----------------------------------------
DO $$
BEGIN
    ASSERT (SELECT district_id FROM properties WHERE id = '00000000-0000-0000-0000-0000000000a1') = 9001,
        'property inside the district boundary should get district 9001';
    ASSERT (SELECT count(*) FROM location_data WHERE source = 'test' AND district_id = 9001) = 4,
        'the four POIs inside the box should be assigned to the district (the 2.2 km one is outside)';
END $$;

-- 4. Geographical search ---------------------------------------------------------------
DO $$
DECLARE
    pid constant uuid := '00000000-0000-0000-0000-0000000000a1';
    r record;
BEGIN
    -- Within 1 km: supermarket, pharmacy, school, metro — nearest first, far pharmacy excluded.
    ASSERT (SELECT array_agg(name ORDER BY distance_m) FROM property_nearby_places(pid, 1000))
         = ARRAY['Supermarket 90 m', 'Pharmacy 220 m north', 'School 450 m south', 'Metro 530 m east'],
        'nearby places within 1 km, ordered by distance';
    ASSERT (SELECT array_agg(name ORDER BY distance_m) FROM property_nearby_places(pid, 1000))
         = (SELECT array_agg(name) FROM property_nearby_places(pid, 1000)),
        'results are returned nearest first';

    -- Category filter.
    ASSERT (SELECT count(*) FROM property_nearby_places(pid, 5000, ARRAY['pharmacy'])) = 2, 'two pharmacies within 5 km';
    ASSERT (SELECT count(*) FROM property_nearby_places(pid, 1000, ARRAY['pharmacy'])) = 1, 'one pharmacy within 1 km';

    -- Distances are real metres (spheroid), within a few metres of the expected values.
    SELECT * INTO r FROM property_nearby_places(pid, 1000, ARRAY['pharmacy']);
    ASSERT r.distance_m BETWEEN 215 AND 230, format('pharmacy distance %s m, expected ≈222 m', r.distance_m);
    SELECT * INTO r FROM property_nearby_places(pid, 1000, ARRAY['metro']);
    ASSERT r.distance_m BETWEEN 520 AND 540, format('metro distance %s m, expected ≈527 m', r.distance_m);

    -- Nearest of each category; categories with nothing in range are omitted.
    ASSERT (SELECT array_agg(category ORDER BY distance_m) FROM nearest_by_category(pid))
         = ARRAY['supermarket', 'pharmacy', 'school', 'metro'],
        'nearest per category';
    ASSERT (SELECT name FROM nearest_by_category(pid, ARRAY['pharmacy'])) = 'Pharmacy 220 m north',
        'nearest pharmacy is the close one';

    -- Summary counts.
    ASSERT (SELECT places FROM location_summary(pid, 5000) WHERE category = 'pharmacy') = 2, 'pharmacy count within 5 km';

    -- Point-based search works without a saved property.
    ASSERT (SELECT count(*) FROM nearby_places(geo_point(23.7348, 37.9955), 300, ARRAY['pharmacy'])) = 1,
        'point search around the far pharmacy';

    -- A property without a location returns nothing rather than failing.
    UPDATE properties SET location = NULL WHERE id = pid;
    ASSERT (SELECT count(*) FROM property_nearby_places(pid)) = 0, 'no location → no results';
    UPDATE properties SET location = geo_point(23.7348, 37.9755) WHERE id = pid;
END $$;

-- 5. Comparables: filtered by type, size, radius and date, ranked by similarity ---------
INSERT INTO comparables (id, source, source_ref, listing_type, location, property_type, size_sqm, bedrooms, price, observed_on) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'test', 'c1', 'sold',         geo_point(23.7350, 37.9757), 'apartment', 82, 2, 262000, current_date - 30),
    ('00000000-0000-0000-0000-0000000000c2', 'test', 'c2', 'sale-listing', geo_point(23.7400, 37.9790), 'apartment', 95, 3, 300000, current_date - 60),
    ('00000000-0000-0000-0000-0000000000c3', 'test', 'c3', 'sold',         geo_point(23.7350, 37.9757), 'maisonette', 80, 2, 300000, current_date - 30), -- wrong type
    ('00000000-0000-0000-0000-0000000000c4', 'test', 'c4', 'sold',         geo_point(23.7350, 37.9757), 'apartment', 150, 4, 500000, current_date - 30), -- too large
    ('00000000-0000-0000-0000-0000000000c5', 'test', 'c5', 'sold',         geo_point(23.7350, 37.9757), 'apartment', 80, 2, 200000, current_date - 1500), -- too old
    ('00000000-0000-0000-0000-0000000000c6', 'test', 'c6', 'rent-listing', geo_point(23.7350, 37.9757), 'apartment', 80, 2, 900, current_date - 10); -- rental

DO $$
DECLARE
    pid constant uuid := '00000000-0000-0000-0000-0000000000a1';
BEGIN
    ASSERT (SELECT array_agg(comparable_id::text ORDER BY similarity DESC) FROM find_comparables(pid))
         = ARRAY['00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000c2'],
        'only same-type, similar-size, recent sales within range, best match first';
    ASSERT (SELECT similarity FROM find_comparables(pid) WHERE comparable_id = '00000000-0000-0000-0000-0000000000c1') > 0.9,
        'near-identical neighbour scores above 0.9';
    ASSERT (SELECT count(*) FROM find_comparables(pid, p_listing_types => ARRAY['rent-listing'])) = 1, 'rental comparables';
    ASSERT (SELECT price_per_sqm FROM comparables WHERE source_ref = 'c1') = 3195.12, 'generated price per sqm';
END $$;

-- 5b. V6: point and district comparable search, valuation outcome rules ------------------
UPDATE comparables SET elevator = true, parking = false, bathrooms = 1, verified = true WHERE source_ref = 'c1';

DO $$
BEGIN
    ASSERT (SELECT array_agg(listing_type ORDER BY distance_m) FROM search_comparables(
                geo_point(23.7348, 37.9755), NULL, 'apartment', ARRAY['sold', 'sale-listing'], 80))
         = ARRAY['sold', 'sale-listing'], 'point search: same type, sales only, nearest first';
    ASSERT (SELECT elevator AND verified FROM search_comparables(
                geo_point(23.7348, 37.9755), NULL, 'apartment', ARRAY['sold'], 80)), 'V6 attributes returned';
    ASSERT (SELECT count(*) FROM search_comparables(
                geo_point(23.7348, 37.9755), NULL, 'apartment', ARRAY['sold'], 80, p_radius_m => 50)) = 1,
        'radius respected (c1 is about 28 m away, c2 about 600 m)';
    ASSERT (SELECT count(*) FROM search_comparables(
                NULL, 'No such district', 'apartment', ARRAY['sold'], 80)) = 0, 'unknown district → nothing';
    ASSERT (SELECT count(*) FROM search_comparables(
                NULL, NULL, 'apartment', ARRAY['sold'], 80)) = 0, 'no point and no district → nothing';
    ASSERT (SELECT count(*) FROM search_comparables(
                geo_point(23.7348, 37.9755), NULL, 'apartment', ARRAY['rent-listing'], 80)) = 1, 'rental search';
END $$;

DO $$
BEGIN
    -- An insufficient-data run is recorded without figures…
    INSERT INTO valuations (property_id, method, engine, confidence, status, data_quality)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'comparables', 'test', 0, 'insufficient-data',
            '{"grade": "insufficient"}');
    -- …and can never carry one.
    BEGIN
        INSERT INTO valuations (property_id, method, engine, estimate_low, estimate_mid, estimate_high, confidence, status)
        VALUES ('00000000-0000-0000-0000-0000000000a1', 'comparables', 'test', 1, 2, 3, 0, 'insufficient-data');
        RAISE EXCEPTION 'insufficient-data valuation with figures was accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO valuations (property_id, method, engine, confidence) VALUES
            ('00000000-0000-0000-0000-0000000000a1', 'comparables', 'test', 50);
        RAISE EXCEPTION 'valued valuation without figures was accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    ASSERT (SELECT count(*) FROM property_latest_valuation
            WHERE property_id = '00000000-0000-0000-0000-0000000000a1') = 0,
        'latest-valuation view ignores insufficient-data runs';
END $$;

-- 5c. V7: environmental risks — covered vs not assessed, highest zone wins -----------------
INSERT INTO environmental_risk_layers (id, risk_type, name, source, coverage) VALUES
    (9001, 'flood', 'Test flood layer', 'test', ST_Multi(ST_MakeEnvelope(23.70, 37.95, 23.80, 38.00, 4326))::geography);
INSERT INTO environmental_risk_zones (layer_id, level, description, area) VALUES
    (9001, 'medium', 'Test medium zone', ST_Multi(ST_MakeEnvelope(23.73, 37.97, 23.74, 37.98, 4326))::geography),
    (9001, 'high',   'Test high zone',   ST_Multi(ST_MakeEnvelope(23.734, 37.974, 23.736, 37.976, 4326))::geography);

DO $$
BEGIN
    ASSERT (SELECT level FROM environmental_risks_at(geo_point(23.7348, 37.9755)) WHERE layer_name = 'Test flood layer') = 'high',
        'inside both zones: the higher level is reported';
    ASSERT (SELECT covered AND level IS NULL FROM environmental_risks_at(geo_point(23.79, 37.99)) WHERE layer_name = 'Test flood layer'),
        'covered by the layer but outside every zone';
    ASSERT NOT (SELECT covered FROM environmental_risks_at(geo_point(22.0, 39.0)) WHERE layer_name = 'Test flood layer'),
        'outside the layer coverage: not assessed';
    ASSERT (SELECT count(*) FROM location_summary_at(geo_point(23.7348, 37.9755), 1000)) >= 0, 'location_summary_at runs';
    BEGIN
        INSERT INTO documents (property_id, document_type, storage_key, file_name, content_type, size_bytes)
        VALUES ('00000000-0000-0000-0000-0000000000a1', 'engineer-certificate', 'test/v7-doc.pdf', 'cert.pdf', 'application/pdf', 10);
    EXCEPTION WHEN check_violation THEN RAISE EXCEPTION 'V7 document type rejected';
    END;
    BEGIN
        INSERT INTO floor_plan_readings (property_id, model, served_by, legibility) VALUES
            ('00000000-0000-0000-0000-0000000000a1', 'm', 'm', 'blurry');
        RAISE EXCEPTION 'invalid legibility accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
END $$;

-- 6. Evaluation records: valuations, investments, expenses, reports ---------------------
INSERT INTO valuations (id, property_id, method, engine, estimate_low, estimate_mid, estimate_high, confidence, verdict)
VALUES ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000a1', 'comparables', 'test',
        240000, 255000, 270000, 72, 'fair');
INSERT INTO valuation_comparables (valuation_id, comparable_id, distance_m, similarity, weight)
SELECT '00000000-0000-0000-0000-0000000000d1', comparable_id, distance_m, similarity, 0.5
FROM find_comparables('00000000-0000-0000-0000-0000000000a1');

INSERT INTO investments (id, property_id, purchase_price, purchase_costs, renovation_costs, monthly_rent,
                         occupancy_pct, annual_operating_costs)
VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000a1',
        250000, 20000, 30000, 1000, 100, 3000);

INSERT INTO expenses (property_id, stage, category, amount, frequency) VALUES
    ('00000000-0000-0000-0000-0000000000a1', 'purchase',  'transfer-tax',   7750, 'one-off'),
    ('00000000-0000-0000-0000-0000000000a1', 'ownership', 'common-charges', 60,   'monthly'),
    ('00000000-0000-0000-0000-0000000000a1', 'ownership', 'property-tax',   420,  'annual');

DO $$
DECLARE
    inv investments%ROWTYPE;
BEGIN
    SELECT * INTO inv FROM investments WHERE id = '00000000-0000-0000-0000-0000000000e1';
    ASSERT inv.total_investment = 300000, 'total investment = price + costs + renovation';
    ASSERT inv.gross_yield_pct = 4.80, format('gross yield %s, expected 4.80', inv.gross_yield_pct);
    ASSERT inv.net_yield_pct = 3.00, format('net yield %s, expected 3.00', inv.net_yield_pct);

    ASSERT (SELECT sum(annual_amount) FROM expenses
            WHERE property_id = '00000000-0000-0000-0000-0000000000a1' AND stage = 'ownership') = 1140,
        'annualised ownership costs';
    ASSERT (SELECT annual_amount FROM expenses
            WHERE property_id = '00000000-0000-0000-0000-0000000000a1' AND stage = 'purchase') IS NULL,
        'one-off costs are not annualised';

    ASSERT (SELECT estimate_mid FROM property_latest_valuation
            WHERE property_id = '00000000-0000-0000-0000-0000000000a1') = 255000, 'latest valuation view';
    ASSERT (SELECT count(*) FROM valuation_comparables WHERE valuation_id = '00000000-0000-0000-0000-0000000000d1') = 2,
        'valuation records the comparables it used';
END $$;

-- 7. Constraints reject inconsistent data ------------------------------------------------
SELECT pg_temp.expect_error($$
    INSERT INTO valuations (property_id, method, engine, estimate_low, estimate_mid, estimate_high, confidence)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'manual', 't', 300000, 250000, 280000, 50) $$, '23514');  -- low > mid
SELECT pg_temp.expect_error($$
    INSERT INTO valuations (property_id, method, engine, estimate_low, estimate_mid, estimate_high, confidence)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'manual', 't', 1, 2, 3, 101) $$, '23514');                -- confidence > 100
SELECT pg_temp.expect_error($$
    INSERT INTO renovations (property_id, item, category, priority, cost_low, cost_high)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'Tiles', 'bathroom', 'optional', 5000, 2000) $$, '23514'); -- low > high
SELECT pg_temp.expect_error($$
    INSERT INTO inspections (property_id, source, category, severity, title)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'ai-vision', 'damp', 'major', 'Damp patch') $$, '23514');  -- AI finding without confidence
SELECT pg_temp.expect_error($$
    INSERT INTO reports (property_id, report_type, status, title)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'full-evaluation', 'ready', 'R') $$, '23514');            -- ready without a file
SELECT pg_temp.expect_error($$
    INSERT INTO expenses (property_id, stage, category, amount, frequency)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'purchase', 'notary', 100, 'monthly') $$, '23514');        -- recurring purchase cost
SELECT pg_temp.expect_error($$
    INSERT INTO investments (property_id, purchase_price, monthly_rent, loan_amount)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 200000, 800, 150000) $$, '23514');                        -- loan without rate/term
SELECT pg_temp.expect_error($$
    INSERT INTO property_images (property_id, storage_key, file_name, content_type, size_bytes)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'k1', 'a.pdf', 'application/pdf', 10) $$, '23514');      -- not an image
SELECT pg_temp.expect_error($$
    INSERT INTO users (email, password_hash, full_name) VALUES ('test.buyer@EXAMPLE.test', 'x', 'Dup') $$, '23505'); -- email case-insensitive unique
SELECT pg_temp.expect_error($$
    INSERT INTO comparables (source, source_ref, listing_type, location, property_type, size_sqm, price, observed_on, price_per_sqm)
    VALUES ('test', 'cx', 'sold', geo_point(23.73, 37.97), 'apartment', 80, 1, current_date, 5) $$, '428C9');  -- generated column is read-only

-- 7b. Property submission (V4) ----------------------------------------------------------
UPDATE properties
SET construction_type = 'reinforced-concrete', cooling = 'split-units',
    energy_certificate_status = 'issued', energy_certificate_number = 'TEST-123',
    other_facilities = 'Shared laundry'
WHERE id = '00000000-0000-0000-0000-0000000000a1';
UPDATE buildings SET common_charges_include_heating = true, planned_works = 'Facade repair'
WHERE id = '00000000-0000-0000-0000-0000000000b1';

INSERT INTO appliance_checklist (property_id, item, status) VALUES
    ('00000000-0000-0000-0000-0000000000a1', 'refrigerator', 'working'),
    ('00000000-0000-0000-0000-0000000000a1', 'washing-machine', 'damaged'),
    ('00000000-0000-0000-0000-0000000000a1', 'dishwasher', 'missing'),
    ('00000000-0000-0000-0000-0000000000a1', 'oven-stove', 'unknown'),
    ('00000000-0000-0000-0000-0000000000a1', 'air-conditioning', 'working'),
    ('00000000-0000-0000-0000-0000000000a1', 'heating', 'available'),
    ('00000000-0000-0000-0000-0000000000a1', 'furniture', 'partial');
INSERT INTO property_features (property_id, category, name) VALUES
    ('00000000-0000-0000-0000-0000000000a1', 'facility', 'security-door'),
    ('00000000-0000-0000-0000-0000000000a1', 'facility', 'awnings');
INSERT INTO property_images (property_id, storage_key, file_name, content_type, size_bytes, area)
SELECT '00000000-0000-0000-0000-0000000000a1', 'sub/k' || g, 'k.jpg', 'image/jpeg', 100, 'kitchen'
FROM generate_series(1, 8) g;

-- Statuses are checked per item.
SELECT pg_temp.expect_error($$
    INSERT INTO appliance_checklist (property_id, item, status)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'heating', 'working') $$, '23514');     -- appliance status on heating
SELECT pg_temp.expect_error($$
    UPDATE appliance_checklist SET status = 'included'
    WHERE property_id = '00000000-0000-0000-0000-0000000000a1' AND item = 'dishwasher' $$, '23514'); -- furniture status on an appliance
SELECT pg_temp.expect_error($$
    INSERT INTO appliance_checklist (property_id, item, status)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'sauna', 'working') $$, '23514');       -- unknown item
SELECT pg_temp.expect_error($$
    INSERT INTO appliance_checklist (property_id, item, status)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'refrigerator', 'damaged') $$, '23505'); -- one row per item

-- Photo limits: 8 per area, 24 per property.
SELECT pg_temp.expect_error($$
    INSERT INTO property_images (property_id, storage_key, file_name, content_type, size_bytes, area)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'sub/k9', 'k.jpg', 'image/jpeg', 100, 'kitchen') $$, '23514');
INSERT INTO property_images (property_id, storage_key, file_name, content_type, size_bytes, area)
SELECT '00000000-0000-0000-0000-0000000000a1', 'sub/' || a || g, 'x.jpg', 'image/jpeg', 100, a
FROM unnest(ARRAY['bathroom', 'bedroom']) a, generate_series(1, 8) g;
SELECT pg_temp.expect_error($$
    INSERT INTO property_images (property_id, storage_key, file_name, content_type, size_bytes, area)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'sub/x25', 'x.jpg', 'image/jpeg', 100, 'exterior') $$, '23514');
SELECT pg_temp.expect_error($$
    INSERT INTO property_images (property_id, storage_key, file_name, content_type, size_bytes, area)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'sub/x26', 'x.jpg', 'image/jpeg', 100, 'garage') $$, '23514');  -- unknown area

-- Asking price ranges depend on the listing type; a certificate number needs an issued certificate.
SELECT pg_temp.expect_error($$
    UPDATE properties SET listing_type = 'rent' WHERE id = '00000000-0000-0000-0000-0000000000a1' $$, '23514'); -- €250k/month
SELECT pg_temp.expect_error($$
    UPDATE properties SET energy_certificate_status = 'none' WHERE id = '00000000-0000-0000-0000-0000000000a1' $$, '23514');

DO $$
DECLARE
    s property_submissions%ROWTYPE;
BEGIN
    UPDATE properties SET listing_type = 'rent', asking_price = 950 WHERE id = '00000000-0000-0000-0000-0000000000a1';
    SELECT * INTO s FROM property_submissions WHERE id = '00000000-0000-0000-0000-0000000000a1';
    ASSERT s.listing_type = 'rent' AND s.asking_price = 950, 'rental with a monthly rent';
    ASSERT round(s.lat::numeric, 4) = 37.9755 AND round(s.lon::numeric, 4) = 23.7348, 'location as lat/lon';
    ASSERT s.district = 'Test district', 'district name';
    ASSERT s.checklist ->> 'washing-machine' = 'damaged' AND s.checklist ->> 'furniture' = 'partial', 'checklist object';
    ASSERT (SELECT count(*) FROM jsonb_object_keys(s.checklist)) = 7, 'all seven checklist items';
    ASSERT s.facilities = ARRAY['awnings', 'security-door'], 'facilities';
    ASSERT s.photos_per_area = '{"kitchen": 8, "bathroom": 8, "bedroom": 8}'::jsonb, format('photos per area %s', s.photos_per_area);
    ASSERT s.monthly_common_charges = 60 AND s.common_charges_include_heating AND s.planned_works = 'Facade repair',
        'building expenses';
    UPDATE properties SET listing_type = 'sale', asking_price = 250000 WHERE id = '00000000-0000-0000-0000-0000000000a1';
    DELETE FROM property_images WHERE storage_key LIKE 'sub/%';
END $$;

-- 7c. AI inspections (V5) ------------------------------------------------------------------
INSERT INTO property_images (id, property_id, storage_key, file_name, content_type, size_bytes, area)
VALUES ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a1', 'ai/1.jpg', '1.jpg', 'image/jpeg', 100, 'bathroom');
INSERT INTO ai_inspection_runs (id, property_id, model, served_by, photos_submitted, photos_analysed, area_conditions)
VALUES ('00000000-0000-0000-0000-0000000000e9', '00000000-0000-0000-0000-0000000000a1', 'claude-opus-5-5',
        '{claude-opus-5-5}', 1, 1, '[{"area": "bathroom", "condition": "fair", "summary": "Dated"}]');
INSERT INTO inspections (property_id, run_id, image_id, source, engine, room, category, severity, title, description,
                         confidence, assessment, limitation, recommendation)
VALUES ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000e9', '00000000-0000-0000-0000-0000000000f1',
        'ai-vision', 'claude-opus-5-5', 'bathroom', 'mould-damp', 'moderate', 'Possible: mould on ceiling',
        'Dark patches above the shower.', 80, 'potential-concern', 'Photos cannot show how far moisture has spread.',
        'Ask for a moisture survey.');

SELECT pg_temp.expect_error($$
    INSERT INTO inspections (property_id, run_id, image_id, source, category, severity, title, confidence, assessment, recommendation)
    VALUES ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000e9', '00000000-0000-0000-0000-0000000000f1',
            'ai-vision', 'electrical', 'major', 'Scorched socket', 70, 'observed', 'Electrician') $$, '23514');  -- hidden risk stated as observed
SELECT pg_temp.expect_error($$
    INSERT INTO inspections (property_id, source, category, severity, title, confidence, assessment, recommendation)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'ai-vision', 'flooring', 'minor', 'Chipped tile', 70, 'observed', 'Replace') $$, '23514'); -- AI finding without run and photo
SELECT pg_temp.expect_error($$
    INSERT INTO inspections (property_id, source, category, severity, title, cost_low, cost_high)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'manual', 'flooring', 'minor', 'Tiles', 500, 100) $$, '23514');  -- cost range reversed / no basis
SELECT pg_temp.expect_error($$
    INSERT INTO ai_inspection_runs (property_id, model, photos_submitted, photos_analysed)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'm', 2, 3) $$, '23514');                                           -- more analysed than submitted

DO $$
BEGIN
    ASSERT (SELECT count(*) FROM inspections WHERE run_id = '00000000-0000-0000-0000-0000000000e9') = 1, 'finding linked to its run';
    DELETE FROM ai_inspection_runs WHERE id = '00000000-0000-0000-0000-0000000000e9';
    ASSERT NOT EXISTS (SELECT 1 FROM inspections WHERE run_id = '00000000-0000-0000-0000-0000000000e9'), 'run delete removes its findings';
    DELETE FROM property_images WHERE storage_key = 'ai/1.jpg';
END $$;

-- 8. Uniqueness and cascade rules -----------------------------------------------------------
INSERT INTO property_images (property_id, storage_key, file_name, content_type, size_bytes, is_cover)
VALUES ('00000000-0000-0000-0000-0000000000a1', 'img/1.jpg', '1.jpg', 'image/jpeg', 1000, true);
SELECT pg_temp.expect_error($$
    INSERT INTO property_images (property_id, storage_key, file_name, content_type, size_bytes, is_cover)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'img/2.jpg', '2.jpg', 'image/jpeg', 1000, true) $$, '23505'); -- one cover photo

INSERT INTO saved_properties (user_id, property_id, label)
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'Shortlist');
SELECT pg_temp.expect_error($$
    INSERT INTO saved_properties (user_id, property_id)
    VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1') $$, '23505');      -- saved once

SELECT pg_temp.expect_error($$
    DELETE FROM comparables WHERE id = '00000000-0000-0000-0000-0000000000c1' $$, '23503');                    -- used by a valuation

INSERT INTO subscriptions (user_id, plan_id, status, payment_status) VALUES ('00000000-0000-0000-0000-000000000001', 'premium', 'active', 'paid');
SELECT pg_temp.expect_error($$
    INSERT INTO subscriptions (user_id, plan_id, status) VALUES ('00000000-0000-0000-0000-000000000001', 'business', 'trialing') $$, '23505'); -- one live plan

-- V8: plans match the backend's PlanCatalog; usage counted once per property per month ---------
DO $$
BEGIN
    ASSERT (SELECT array_agg(id || ':' || coalesce(monthly_price_cents::text, 'custom') || ':' || coalesce(analyses_per_month::text, 'contract')
                             ORDER BY coalesce(monthly_price_cents, 1000000))
            FROM plans) = ARRAY['free:0:2', 'premium:999:20', 'professional:2999:100', 'business:custom:contract'],
        'plan prices and limits are the Phase 9 hypotheses (keep in step with PlanCatalog.java)';
    ASSERT (SELECT 'api-access' = ANY (features) AND 'teams' = ANY (features) FROM plans WHERE id = 'business'), 'business features';
    ASSERT NOT (SELECT 'investment' = ANY (features) FROM plans WHERE id = 'premium'), 'investment tools start at Professional';
    ASSERT (SELECT max_inspection_photos FROM plans WHERE id = 'free') = 6, 'free inspects 6 photos';

    INSERT INTO usage_events (account_user_id, period, property_ref, endpoint)
    VALUES ('00000000-0000-0000-0000-000000000001', '2026-11-01', 'p:a1', '/api/v1/valuations');
    BEGIN
        INSERT INTO usage_events (account_user_id, period, property_ref, endpoint)
        VALUES ('00000000-0000-0000-0000-000000000001', '2026-11-01', 'p:a1', '/api/v1/reports/pdf');
        RAISE EXCEPTION 'same property counted twice in a month';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    INSERT INTO usage_events (account_user_id, period, property_ref, endpoint)
    VALUES ('00000000-0000-0000-0000-000000000001', '2026-12-01', 'p:a1', '/api/v1/valuations');   -- next month counts again
    BEGIN
        INSERT INTO usage_events (account_user_id, period, property_ref, endpoint)
        VALUES ('00000000-0000-0000-0000-000000000001', '2026-11-15', 'p:b', '/api/v1/valuations');
        RAISE EXCEPTION 'period must be the first day of a month';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO plans (id, name) VALUES ('free', 'x');
        RAISE EXCEPTION 'duplicate plan accepted';
    EXCEPTION WHEN unique_violation OR not_null_violation OR check_violation THEN NULL;
    END;
    INSERT INTO user_sessions (token_hash, user_id, expires_at)
    VALUES (repeat('a', 64), '00000000-0000-0000-0000-000000000001', now() + interval '1 day');
    INSERT INTO api_keys (owner_user_id, name, prefix, key_hash)
    VALUES ('00000000-0000-0000-0000-000000000001', 'test', 'pe_live_abc', repeat('b', 64));
END $$;

-- V9: consent, uploaded files, audit log, retention -------------------------------------
DO $$
DECLARE u uuid := '00000000-0000-0000-0000-000000000001'; r record; n bigint;
BEGIN
    INSERT INTO consent_records (user_id, purpose, granted, policy_version, source, recorded_at)
    VALUES (u, 'marketing-email', true, '2026-11-25', 'registration', now() - interval '1 day'),
           (u, 'marketing-email', false, '2026-11-25', 'settings', now());
    ASSERT (SELECT granted FROM current_consents WHERE user_id = u AND purpose = 'marketing-email') = false,
        'current_consents shows the latest decision';
    PERFORM pg_temp.expect_error($q$UPDATE consent_records SET granted = true$q$, 'P0001');
    PERFORM pg_temp.expect_error($q$INSERT INTO consent_records (user_id, purpose, granted, policy_version, source)
        VALUES ('00000000-0000-0000-0000-000000000001', 'profiling', true, 'v', 'settings')$q$, '23514');

    INSERT INTO stored_files (storage_key, owner_user_id, uploaded_by, property_ref, kind, content_type, size_bytes, delete_after)
    VALUES ('accounts/' || u || '/properties/p1/a.jpg', u, u, 'p1', 'inspection-photo', 'image/jpeg', 1024, now() + interval '180 days');
    PERFORM pg_temp.expect_error($q$INSERT INTO stored_files (storage_key, owner_user_id, property_ref, kind, content_type, size_bytes, delete_after)
        VALUES ('x.exe', '00000000-0000-0000-0000-000000000001', 'p1', 'inspection-photo', 'application/x-msdownload', 1, now() + interval '1 day')$q$, '23514');

    INSERT INTO audit_events (actor_user_id, actor_kind, action, outcome) VALUES (u, 'user', 'auth.login', 'success');
    INSERT INTO audit_events (actor_kind, action, outcome, occurred_at) VALUES ('system', 'retention.purge', 'success', now() - interval '400 days');
    PERFORM pg_temp.expect_error($q$INSERT INTO audit_events (actor_kind, action, outcome) VALUES ('user', 'Login!', 'success')$q$, '23514');
    -- Append-only: no edits, no deletes outside the retention purge.
    PERFORM pg_temp.expect_error($q$UPDATE audit_events SET outcome = 'failure'$q$, 'P0001');
    PERFORM pg_temp.expect_error($q$DELETE FROM audit_events$q$, 'P0001');

    -- Retention: old audit events, expired sessions and old usage go; recent data stays.
    INSERT INTO user_sessions (token_hash, user_id, created_at, expires_at)
    VALUES (repeat('e', 64), u, now() - interval '40 days', now() - interval '10 days');
    FOR r IN SELECT * FROM purge_expired_data(now()) LOOP
        IF r.category = 'audit-events' THEN ASSERT r.deleted = 1, format('one old audit event purged, got %s', r.deleted); END IF;
        IF r.category = 'expired-sessions' THEN ASSERT r.deleted >= 1, 'expired session purged'; END IF;
    END LOOP;
    ASSERT NOT EXISTS (SELECT 1 FROM audit_events WHERE action = 'retention.purge' AND occurred_at < now() - interval '365 days'),
        'audit events older than the retention period are gone';
    ASSERT EXISTS (SELECT 1 FROM audit_events WHERE actor_user_id = u AND action = 'auth.login'), 'recent audit events stay';
    ASSERT NOT EXISTS (SELECT 1 FROM user_sessions WHERE token_hash = repeat('e', 64)), 'expired session gone';
    ASSERT EXISTS (SELECT 1 FROM user_sessions WHERE token_hash = repeat('a', 64)), 'live session kept';
    PERFORM pg_temp.expect_error($q$DELETE FROM audit_events$q$, 'P0001');  -- the purge switch does not leak
END $$;

DO $$
BEGIN
    -- updated_at trigger overrides whatever the client sends.
    UPDATE properties SET updated_at = '2000-01-01' WHERE id = '00000000-0000-0000-0000-0000000000a1';
    ASSERT (SELECT updated_at FROM properties WHERE id = '00000000-0000-0000-0000-0000000000a1') > '2000-01-02',
        'updated_at is maintained by the trigger';

    -- Deleting the account removes the user's properties and everything hanging off them.
    DELETE FROM users WHERE id = '00000000-0000-0000-0000-000000000001';
    ASSERT NOT EXISTS (SELECT 1 FROM properties WHERE id = '00000000-0000-0000-0000-0000000000a1'), 'property cascaded';
    ASSERT NOT EXISTS (SELECT 1 FROM valuations WHERE property_id = '00000000-0000-0000-0000-0000000000a1'), 'valuations cascaded';
    ASSERT NOT EXISTS (SELECT 1 FROM expenses WHERE property_id = '00000000-0000-0000-0000-0000000000a1'), 'expenses cascaded';
    ASSERT NOT EXISTS (SELECT 1 FROM saved_properties WHERE user_id = '00000000-0000-0000-0000-000000000001'), 'saved cascaded';
    ASSERT NOT EXISTS (SELECT 1 FROM usage_events WHERE account_user_id = '00000000-0000-0000-0000-000000000001'), 'usage cascaded';
    ASSERT NOT EXISTS (SELECT 1 FROM user_sessions WHERE user_id = '00000000-0000-0000-0000-000000000001'), 'sessions cascaded';
    ASSERT NOT EXISTS (SELECT 1 FROM api_keys WHERE owner_user_id = '00000000-0000-0000-0000-000000000001'), 'API keys cascaded';
    ASSERT NOT EXISTS (SELECT 1 FROM consent_records WHERE user_id = '00000000-0000-0000-0000-000000000001'), 'consents cascaded';
    ASSERT NOT EXISTS (SELECT 1 FROM stored_files WHERE owner_user_id = '00000000-0000-0000-0000-000000000001'), 'file records cascaded';
    ASSERT EXISTS (SELECT 1 FROM audit_events WHERE action = 'auth.login' AND actor_user_id IS NULL),
        'audit events outlive the account, without the actor';
    ASSERT EXISTS (SELECT 1 FROM buildings WHERE id = '00000000-0000-0000-0000-0000000000b1'), 'shared building is kept';
    ASSERT EXISTS (SELECT 1 FROM comparables WHERE id = '00000000-0000-0000-0000-0000000000c1'), 'market records are kept';
END $$;

ROLLBACK;
\o
\echo 'All schema tests passed.'
