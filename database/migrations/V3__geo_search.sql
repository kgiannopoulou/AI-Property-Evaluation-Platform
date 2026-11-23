-- V3: PostGIS geographical search.
--
-- All distances are metres on the WGS 84 spheroid (geography type). Every
-- search filters with ST_DWithin, which uses the GiST indexes from V1/V2, and
-- orders with the <-> operator for index-assisted nearest-neighbour lookups.

-- District assignment ----------------------------------------------------------------
-- A row with a location but no district gets the district whose boundary covers it.
CREATE OR REPLACE FUNCTION assign_district() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.location IS NOT NULL AND NEW.district_id IS NULL THEN
        SELECT d.id INTO NEW.district_id
        FROM districts d
        WHERE d.boundary IS NOT NULL AND ST_Covers(d.boundary, NEW.location)
        ORDER BY ST_Area(d.boundary)          -- most specific district wins if boundaries overlap
        LIMIT 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER properties_assign_district BEFORE INSERT OR UPDATE OF location, district_id ON properties
    FOR EACH ROW EXECUTE FUNCTION assign_district();
CREATE TRIGGER comparables_assign_district BEFORE INSERT OR UPDATE OF location, district_id ON comparables
    FOR EACH ROW EXECUTE FUNCTION assign_district();
CREATE TRIGGER location_data_assign_district BEFORE INSERT OR UPDATE OF location, district_id ON location_data
    FOR EACH ROW EXECUTE FUNCTION assign_district();

-- Helper: build a geography point from longitude/latitude ---------------------------------
CREATE OR REPLACE FUNCTION geo_point(p_lon double precision, p_lat double precision)
RETURNS geography
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT ST_SetSRID(ST_MakePoint(p_lon, p_lat), 4326)::geography
$$;

-- Facilities around any point -----------------------------------------------------------
-- e.g. SELECT * FROM nearby_places(geo_point(23.7275, 37.9838), 800, ARRAY['pharmacy', 'metro']);
CREATE OR REPLACE FUNCTION nearby_places(
    p_location   geography,
    p_radius_m   double precision DEFAULT 1000,
    p_categories text[]           DEFAULT NULL,   -- NULL = every category
    p_limit      integer          DEFAULT 50
)
RETURNS TABLE (place_id uuid, category text, name text, address text, distance_m double precision, location geography)
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT l.id, l.category, l.name, l.address,
           round(ST_Distance(l.location, p_location)::numeric, 1)::double precision,
           l.location
    FROM location_data l
    WHERE ST_DWithin(l.location, p_location, p_radius_m)
      AND (p_categories IS NULL OR l.category = ANY (p_categories))
    ORDER BY l.location <-> p_location
    LIMIT p_limit
$$;

-- Facilities around a saved property (empty when the property has no location yet).
CREATE OR REPLACE FUNCTION property_nearby_places(
    p_property_id uuid,
    p_radius_m    double precision DEFAULT 1000,
    p_categories  text[]           DEFAULT NULL,
    p_limit       integer          DEFAULT 50
)
RETURNS TABLE (place_id uuid, category text, name text, address text, distance_m double precision, location geography)
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT n.*
    FROM properties p
    CROSS JOIN LATERAL nearby_places(p.location, p_radius_m, p_categories, p_limit) n
    WHERE p.id = p_property_id AND p.location IS NOT NULL
$$;

-- Closest facility of each requested category: "nearest pharmacy 220 m, metro 640 m…".
CREATE OR REPLACE FUNCTION nearest_by_category(
    p_property_id uuid,
    p_categories  text[]           DEFAULT ARRAY['pharmacy', 'supermarket', 'school', 'metro', 'bus-stop', 'park', 'hospital'],
    p_max_m       double precision DEFAULT 5000
)
RETURNS TABLE (category text, place_id uuid, name text, distance_m double precision)
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT c.category, n.id, n.name, round(ST_Distance(n.location, p.location)::numeric, 1)::double precision
    FROM properties p
    CROSS JOIN unnest(p_categories) AS c(category)
    CROSS JOIN LATERAL (
        SELECT l.id, l.name, l.location
        FROM location_data l
        WHERE l.category = c.category AND ST_DWithin(l.location, p.location, p_max_m)
        ORDER BY l.location <-> p.location
        LIMIT 1
    ) n
    WHERE p.id = p_property_id AND p.location IS NOT NULL
    ORDER BY 4
$$;

-- Counts per category within walking distance, for the location score.
CREATE OR REPLACE FUNCTION location_summary(
    p_property_id uuid,
    p_radius_m    double precision DEFAULT 1000
)
RETURNS TABLE (category text, places bigint, nearest_m double precision)
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT l.category, count(*), round(min(ST_Distance(l.location, p.location))::numeric, 1)::double precision
    FROM properties p
    JOIN location_data l ON ST_DWithin(l.location, p.location, p_radius_m)
    WHERE p.id = p_property_id
    GROUP BY l.category
    ORDER BY l.category
$$;

-- Comparable search ------------------------------------------------------------------------
-- Same property type, similar size, recent, within the radius; ranked by a
-- simple similarity score (distance 50 %, size 30 %, bedrooms 20 %).
CREATE OR REPLACE FUNCTION find_comparables(
    p_property_id     uuid,
    p_radius_m        double precision DEFAULT 1500,
    p_listing_types   text[]           DEFAULT ARRAY['sale-listing', 'sold'],
    p_size_tolerance  numeric          DEFAULT 0.30,   -- ±30 % of the property's size
    p_since           date             DEFAULT (current_date - 730),
    p_limit           integer          DEFAULT 20
)
RETURNS TABLE (comparable_id uuid, listing_type text, size_sqm numeric, bedrooms smallint, price numeric,
               price_per_sqm numeric, observed_on date, distance_m double precision, similarity numeric)
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT s.id, s.listing_type, s.size_sqm, s.bedrooms, s.price, s.price_per_sqm, s.observed_on, s.distance_m,
           round(greatest(0, 1
               - 0.5 * s.distance_m / p_radius_m
               - 0.3 * abs(s.size_sqm - s.subject_size) / (s.subject_size * p_size_tolerance)
               - 0.2 * least(abs(coalesce(s.bedrooms, s.subject_bedrooms) - s.subject_bedrooms), 3) / 3.0
           )::numeric, 3) AS similarity
    FROM (
        SELECT c.*, p.size_sqm AS subject_size, p.bedrooms AS subject_bedrooms,
               round(ST_Distance(c.location, p.location)::numeric, 1)::double precision AS distance_m
        FROM properties p
        JOIN comparables c
          ON ST_DWithin(c.location, p.location, p_radius_m)
         AND c.property_type = p.property_type
         AND c.listing_type = ANY (p_listing_types)
         AND c.size_sqm BETWEEN p.size_sqm * (1 - p_size_tolerance) AND p.size_sqm * (1 + p_size_tolerance)
         AND c.observed_on >= p_since
        WHERE p.id = p_property_id AND p.location IS NOT NULL
    ) s
    ORDER BY similarity DESC, s.distance_m
    LIMIT p_limit
$$;

-- Latest valuation per property (for lists and the dashboard) --------------------------------
CREATE VIEW property_latest_valuation AS
SELECT DISTINCT ON (v.property_id)
       v.property_id, v.id AS valuation_id, v.method, v.estimate_low, v.estimate_mid, v.estimate_high,
       v.confidence, v.verdict, v.valued_at
FROM valuations v
ORDER BY v.property_id, v.valued_at DESC;
