-- Demo data for local development. NOT real listings, prices or businesses.
--
--   docker compose exec -T db psql -U property -d property_intelligence < database/seeds/demo_seed.sql
--
-- Coordinates are real places in central Athens so the map and distance
-- searches behave realistically; names, prices and findings are invented and
-- every row is tagged with source 'demo-seed' or the [DEMO] prefix.
-- Safe to run more than once: it removes the previous demo rows first.

\set ON_ERROR_STOP 1
BEGIN;

-- Remove an earlier copy (user delete cascades to the property and everything on it).
DELETE FROM users       WHERE id = 'd0000000-0000-4000-8000-000000000001';
DELETE FROM buildings   WHERE id = 'd0000000-0000-4000-8000-0000000000b1';
DELETE FROM comparables WHERE source = 'demo-seed';
DELETE FROM location_data WHERE source = 'demo-seed';
DELETE FROM districts   WHERE source = 'demo-seed';
DELETE FROM environmental_risk_layers WHERE source = 'demo-seed';

INSERT INTO districts (name, boundary, sale_per_sqm, rent_per_sqm, location_score, source) VALUES
    ('[DEMO] Pangrati', ST_Multi(ST_MakeEnvelope(23.737, 37.962, 23.752, 37.973, 4326))::geography, 3600, 13.5, 82, 'demo-seed'),
    ('[DEMO] Koukaki',  ST_Multi(ST_MakeEnvelope(23.718, 37.960, 23.730, 37.968, 4326))::geography, 3900, 14.5, 85, 'demo-seed')
ON CONFLICT (name) DO NOTHING;

-- The password hash is not a valid bcrypt hash, so this account cannot log in.
INSERT INTO users (id, email, password_hash, full_name, role, company, locale)
VALUES ('d0000000-0000-4000-8000-000000000001', 'demo.investor@example.test', '!demo-no-login', '[DEMO] Investor', 'investor', NULL, 'en')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_preferences (user_id) VALUES ('d0000000-0000-4000-8000-000000000001') ON CONFLICT DO NOTHING;

INSERT INTO subscriptions (id, user_id, plan_id, status, payment_status, current_period_start, current_period_end)
VALUES ('d0000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-000000000001', 'premium', 'trialing', 'none',
        now(), now() + interval '14 days')
ON CONFLICT DO NOTHING;

INSERT INTO buildings (id, address, location, year_built, floors_above_ground, units_total, structure_type,
                       structural_condition, facade_condition, roof_condition, common_areas_condition,
                       elevator_count, shared_facilities, monthly_common_charges, has_building_permit)
VALUES ('d0000000-0000-4000-8000-0000000000b1', '[DEMO] Building near Varnava Square', geo_point(23.7440, 37.9665),
        1972, 6, 12, 'reinforced-concrete', 'good', 'fair', 'fair', 'good', 1, '{storage-rooms,pilotis-parking}', 55, true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO properties (id, owner_id, building_id, title, address, location, property_type, size_sqm, bedrooms,
                        bathrooms, floor, year_built, renovated_year, condition, energy_class, heating,
                        parking, storage, elevator, balcony_sqm, asking_price, notes)
VALUES ('d0000000-0000-4000-8000-0000000000a1', 'd0000000-0000-4000-8000-000000000001',
        'd0000000-0000-4000-8000-0000000000b1', '[DEMO] 2-bed apartment, Pangrati', '[DEMO] address withheld',
        geo_point(23.7440, 37.9665), 'apartment', 78, 2, 1, 3, 1972, 2009, 'fair', 'D', 'central-oil',
        false, true, true, 9, 265000, 'Demo record for local development.')
ON CONFLICT (id) DO NOTHING;

UPDATE properties SET construction_type = 'reinforced-concrete', cooling = 'split-units',
                      energy_certificate_status = 'issued'
WHERE id = 'd0000000-0000-4000-8000-0000000000a1';
UPDATE buildings SET common_charges_include_heating = false, planned_works = '[DEMO] Facade repainting voted for spring'
WHERE id = 'd0000000-0000-4000-8000-0000000000b1';

INSERT INTO appliance_checklist (property_id, item, status) VALUES
    ('d0000000-0000-4000-8000-0000000000a1', 'refrigerator', 'working'),
    ('d0000000-0000-4000-8000-0000000000a1', 'washing-machine', 'working'),
    ('d0000000-0000-4000-8000-0000000000a1', 'dishwasher', 'missing'),
    ('d0000000-0000-4000-8000-0000000000a1', 'oven-stove', 'working'),
    ('d0000000-0000-4000-8000-0000000000a1', 'air-conditioning', 'damaged'),
    ('d0000000-0000-4000-8000-0000000000a1', 'heating', 'available'),
    ('d0000000-0000-4000-8000-0000000000a1', 'furniture', 'not-included');

INSERT INTO property_features (property_id, category, name, quantity, area_sqm, condition) VALUES
    ('d0000000-0000-4000-8000-0000000000a1', 'facility', 'security-door', 1, NULL, NULL),
    ('d0000000-0000-4000-8000-0000000000a1', 'facility', 'solar-water-heater', 1, NULL, NULL),
    ('d0000000-0000-4000-8000-0000000000a1', 'room', 'bedroom', 2, 24, 'fair'),
    ('d0000000-0000-4000-8000-0000000000a1', 'room', 'living-room', 1, 26, 'good'),
    ('d0000000-0000-4000-8000-0000000000a1', 'room', 'kitchen', 1, 9, 'fair'),
    ('d0000000-0000-4000-8000-0000000000a1', 'room', 'bathroom', 1, 5, 'poor'),
    ('d0000000-0000-4000-8000-0000000000a1', 'facility', 'storage-room', 1, 6, 'good'),
    ('d0000000-0000-4000-8000-0000000000a1', 'amenity', 'acropolis-view', 1, NULL, NULL)
ON CONFLICT DO NOTHING;

INSERT INTO equipment (property_id, category, name, room, condition, approx_age_years, included_in_sale) VALUES
    ('d0000000-0000-4000-8000-0000000000a1', 'heating-cooling', 'air-conditioner', 'living-room', 'good', 6, true),
    ('d0000000-0000-4000-8000-0000000000a1', 'appliance', 'oven', 'kitchen', 'fair', 12, true),
    ('d0000000-0000-4000-8000-0000000000a1', 'appliance', 'solar-water-heater', NULL, 'fair', 15, true),
    ('d0000000-0000-4000-8000-0000000000a1', 'furniture', 'built-in-wardrobe', 'bedroom', 'good', NULL, true);

INSERT INTO inspections (id, property_id, source, room, category, severity, title, description, status) VALUES
    ('d0000000-0000-4000-8000-0000000000f1', 'd0000000-0000-4000-8000-0000000000a1', 'manual', 'bathroom', 'damp',
     'moderate', '[DEMO] Damp marks above the shower', 'Entered by hand for the demo.', 'open'),
    ('d0000000-0000-4000-8000-0000000000f2', 'd0000000-0000-4000-8000-0000000000a1', 'manual', 'living-room', 'windows',
     'minor', '[DEMO] Single-glazed aluminium windows', NULL, 'confirmed')
ON CONFLICT (id) DO NOTHING;

INSERT INTO renovations (property_id, inspection_id, room, item, category, priority, cost_low, cost_high, value_uplift) VALUES
    ('d0000000-0000-4000-8000-0000000000a1', 'd0000000-0000-4000-8000-0000000000f1', 'bathroom',
     '[DEMO] Waterproof and retile bathroom', 'bathroom', 'recommended', 4500, 7500, 6000),
    ('d0000000-0000-4000-8000-0000000000a1', 'd0000000-0000-4000-8000-0000000000f2', 'living-room',
     '[DEMO] Replace windows with double glazing', 'windows', 'optional', 3500, 5500, 3000);

INSERT INTO expenses (property_id, stage, category, amount, frequency, description) VALUES
    ('d0000000-0000-4000-8000-0000000000a1', 'purchase',  'transfer-tax',   8215, 'one-off', '3.1 % of the price (demo figure)'),
    ('d0000000-0000-4000-8000-0000000000a1', 'purchase',  'notary',         2400, 'one-off', NULL),
    ('d0000000-0000-4000-8000-0000000000a1', 'purchase',  'lawyer',         1300, 'one-off', NULL),
    ('d0000000-0000-4000-8000-0000000000a1', 'ownership', 'common-charges', 55,   'monthly', NULL),
    ('d0000000-0000-4000-8000-0000000000a1', 'ownership', 'property-tax',   380,  'annual',  NULL),
    ('d0000000-0000-4000-8000-0000000000a1', 'ownership', 'insurance',      180,  'annual',  NULL);

INSERT INTO investments (property_id, created_by, scenario_name, purchase_price, purchase_costs, renovation_costs,
                         down_payment, loan_amount, interest_rate_pct, loan_years, monthly_rent, occupancy_pct,
                         annual_operating_costs, annual_appreciation_pct, holding_years)
VALUES ('d0000000-0000-4000-8000-0000000000a1', 'd0000000-0000-4000-8000-000000000001', '[DEMO] Long-term let',
        265000, 11915, 6000, 70000, 212000, 3.90, 25, 1050, 95, 1220, 2.0, 10);

-- Points of interest at real locations, with placeholder names.
INSERT INTO location_data (category, name, location, source, source_ref) VALUES
    ('pharmacy',    '[DEMO] Pharmacy A',            geo_point(23.7432, 37.9672), 'demo-seed', 'poi-1'),
    ('pharmacy',    '[DEMO] Pharmacy B',            geo_point(23.7465, 37.9650), 'demo-seed', 'poi-2'),
    ('supermarket', '[DEMO] Supermarket',           geo_point(23.7452, 37.9660), 'demo-seed', 'poi-3'),
    ('school',      '[DEMO] Primary school',        geo_point(23.7418, 37.9649), 'demo-seed', 'poi-4'),
    ('park',        'National Garden (demo entry)', geo_point(23.7365, 37.9725), 'demo-seed', 'poi-5'),
    ('metro',       'Evangelismos metro (demo entry)', geo_point(23.7468, 37.9762), 'demo-seed', 'poi-6'),
    ('metro',       'Syntagma metro (demo entry)',  geo_point(23.7353, 37.9753), 'demo-seed', 'poi-7'),
    ('tram',        '[DEMO] Tram stop',             geo_point(23.7329, 37.9681), 'demo-seed', 'poi-8'),
    ('bus-stop',    '[DEMO] Bus stop',              geo_point(23.7447, 37.9677), 'demo-seed', 'poi-9'),
    ('hospital',    'Evangelismos hospital (demo entry)', geo_point(23.7467, 37.9768), 'demo-seed', 'poi-10')
ON CONFLICT (source, source_ref) DO NOTHING;

INSERT INTO comparables (source, source_ref, listing_type, location, property_type, size_sqm, bedrooms, floor,
                         year_built, condition, energy_class, price, observed_on) VALUES
    ('demo-seed', 'cmp-1', 'sold',         geo_point(23.7451, 37.9671), 'apartment', 75, 2, 2, 1975, 'fair', 'D', 248000, current_date - 90),
    ('demo-seed', 'cmp-2', 'sold',         geo_point(23.7427, 37.9655), 'apartment', 82, 2, 4, 1970, 'good', 'C', 287000, current_date - 150),
    ('demo-seed', 'cmp-3', 'sale-listing', geo_point(23.7470, 37.9690), 'apartment', 70, 2, 1, 1968, 'needs-renovation', 'E', 205000, current_date - 20),
    ('demo-seed', 'cmp-4', 'sale-listing', geo_point(23.7405, 37.9640), 'apartment', 90, 3, 5, 1980, 'good', 'C', 315000, current_date - 45),
    ('demo-seed', 'cmp-5', 'rent-listing', geo_point(23.7445, 37.9660), 'apartment', 80, 2, 3, 1974, 'good', 'D', 1050, current_date - 15),
    ('demo-seed', 'cmp-6', 'rented',       geo_point(23.7420, 37.9680), 'apartment', 76, 2, 2, 1972, 'fair', 'D', 980, current_date - 120)
ON CONFLICT (source, source_ref) DO NOTHING;

-- More demo sales with the attributes the valuation engine adjusts for (V6), so a local
-- POST /api/v1/valuations near Pangrati has enough evidence. Invented prices.
INSERT INTO comparables (source, source_ref, listing_type, location, property_type, size_sqm, bedrooms, bathrooms,
                         floor, elevator, parking, year_built, renovated_year, condition, energy_class, price,
                         observed_on, verified) VALUES
    ('demo-seed', 'cmp-7',  'sold', geo_point(23.7436, 37.9662), 'apartment', 74, 2, 1, 3, true,  false, 1974, NULL, 'fair', 'D', 241000, current_date - 60,  true),
    ('demo-seed', 'cmp-8',  'sold', geo_point(23.7449, 37.9670), 'apartment', 80, 2, 1, 2, true,  false, 1971, 2008, 'good', 'C', 276000, current_date - 200, true),
    ('demo-seed', 'cmp-9',  'sold', geo_point(23.7428, 37.9668), 'apartment', 72, 2, 1, 1, true,  false, 1969, NULL, 'fair', 'E', 222000, current_date - 300, true),
    ('demo-seed', 'cmp-10', 'sold', geo_point(23.7455, 37.9657), 'apartment', 85, 2, 1, 4, true,  true,  1976, NULL, 'fair', 'D', 284000, current_date - 120, true),
    ('demo-seed', 'cmp-11', 'sold', geo_point(23.7419, 37.9674), 'apartment', 77, 2, 1, 3, true,  false, 1973, NULL, 'fair', 'D', 247000, current_date - 420, true),
    ('demo-seed', 'cmp-12', 'sold', geo_point(23.7462, 37.9679), 'apartment', 70, 2, 1, 2, false, false, 1966, NULL, 'needs-renovation', 'F', 196000, current_date - 30, true)
ON CONFLICT (source, source_ref) DO NOTHING;

UPDATE comparables SET bathrooms = 1, elevator = true, parking = false
WHERE source = 'demo-seed' AND source_ref IN ('cmp-1', 'cmp-2', 'cmp-3', 'cmp-4');

-- A valuation built from the comparables the search returns.
INSERT INTO valuations (id, property_id, method, engine, estimate_low, estimate_mid, estimate_high, price_per_sqm,
                        confidence, asking_price, verdict, explanation)
VALUES ('d0000000-0000-4000-8000-0000000000d1', 'd0000000-0000-4000-8000-0000000000a1', 'sample', 'demo-seed',
        238000, 252000, 266000, 3231, 55, 265000, 'fair', 'Demo valuation, not market data.')
ON CONFLICT (id) DO NOTHING;

INSERT INTO valuation_comparables (valuation_id, comparable_id, distance_m, similarity, weight)
SELECT 'd0000000-0000-4000-8000-0000000000d1', f.comparable_id, f.distance_m, f.similarity,
       round(f.similarity / sum(f.similarity) OVER (), 3)
FROM find_comparables('d0000000-0000-4000-8000-0000000000a1') f
ON CONFLICT DO NOTHING;

INSERT INTO saved_properties (user_id, property_id, label)
VALUES ('d0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000a1', 'Shortlist')
ON CONFLICT DO NOTHING;

INSERT INTO reports (property_id, requested_by, valuation_id, report_type, title, status)
SELECT 'd0000000-0000-4000-8000-0000000000a1', 'd0000000-0000-4000-8000-000000000001',
       'd0000000-0000-4000-8000-0000000000d1', 'full-evaluation', '[DEMO] Full evaluation', 'queued'
WHERE NOT EXISTS (SELECT 1 FROM reports WHERE property_id = 'd0000000-0000-4000-8000-0000000000a1');

-- V7: demo risk layers (invented zones, NOT official flood or noise maps) and building details.
INSERT INTO environmental_risk_layers (risk_type, name, source, coverage) VALUES
    ('flood', '[DEMO] Flood zones, central Athens', 'demo-seed', ST_Multi(ST_MakeEnvelope(23.70, 37.94, 23.78, 37.99, 4326))::geography),
    ('noise', '[DEMO] Road noise, central Athens', 'demo-seed', ST_Multi(ST_MakeEnvelope(23.70, 37.94, 23.78, 37.99, 4326))::geography);
INSERT INTO environmental_risk_zones (layer_id, level, description, area)
SELECT id, 'medium', '[DEMO] Busy avenue, 65-70 dB daytime', ST_Multi(ST_MakeEnvelope(23.742, 37.965, 23.746, 37.968, 4326))::geography
FROM environmental_risk_layers WHERE source = 'demo-seed' AND risk_type = 'noise';

UPDATE buildings SET elevator_installed_year = 1998, elevator_last_inspection = current_date - 400,
                     roof_waterproofing_year = 2008, external_insulation = false, windows = 'single', pilotis = true,
                     reserve_fund = 3200, planned_levy_per_unit = 1400
WHERE id = 'd0000000-0000-4000-8000-0000000000b1';

COMMIT;

\echo 'Demo seed loaded. Try:'
\echo '  SELECT * FROM nearest_by_category(''d0000000-0000-4000-8000-0000000000a1'');'
