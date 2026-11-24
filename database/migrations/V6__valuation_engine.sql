-- V6: property valuation engine (backend/src/main/java/com/propertyapp/valuation).
--
-- The engine values a property from comparable market records, never from a
-- model's guess. It needs more attributes on each record to adjust it to the
-- subject property, a way to record that a valuation was REFUSED because the
-- data was not good enough, and search functions that work from a point (the
-- website's properties are not stored in the database yet).

-- Comparables: attributes the engine adjusts for -------------------------------------------
ALTER TABLE comparables
    ADD COLUMN bathrooms      smallint CHECK (bathrooms IS NULL OR bathrooms BETWEEN 0 AND 10),
    ADD COLUMN elevator       boolean,
    ADD COLUMN parking        boolean,
    ADD COLUMN renovated_year smallint CHECK (renovated_year IS NULL OR renovated_year >= 1850),
    -- How the record was obtained: a registered transaction is stronger evidence than an advert.
    ADD COLUMN verified       boolean NOT NULL DEFAULT false;

ALTER TABLE comparables
    ADD CONSTRAINT comparables_renovated_after_built
    CHECK (renovated_year IS NULL OR year_built IS NULL OR renovated_year >= year_built);

-- Valuations: outcome, scores, data quality and negotiation --------------------------------
-- A valuation can now end as 'insufficient-data': the run is recorded (what was searched,
-- why it was not enough) but no figures are stored, so nothing downstream can show one.
ALTER TABLE valuations
    ALTER COLUMN estimate_low  DROP NOT NULL,
    ALTER COLUMN estimate_mid  DROP NOT NULL,
    ALTER COLUMN estimate_high DROP NOT NULL,
    ADD COLUMN status          text     NOT NULL DEFAULT 'valued' CHECK (status IN ('valued', 'insufficient-data')),
    ADD COLUMN condition_score smallint CHECK (condition_score IS NULL OR condition_score BETWEEN 0 AND 100),
    ADD COLUMN location_score  smallint CHECK (location_score IS NULL OR location_score BETWEEN 0 AND 100),
    ADD COLUMN data_quality    jsonb    NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(data_quality) = 'object'),
    ADD COLUMN negotiation     jsonb    CHECK (negotiation IS NULL OR jsonb_typeof(negotiation) = 'object');

ALTER TABLE valuations ADD CONSTRAINT valuations_figures_match_status CHECK (
    (status = 'valued' AND estimate_low IS NOT NULL AND estimate_mid IS NOT NULL AND estimate_high IS NOT NULL)
    OR (status = 'insufficient-data' AND estimate_low IS NULL AND estimate_mid IS NULL AND estimate_high IS NULL
        AND verdict IS NULL AND negotiation IS NULL)
);

-- The latest-valuation view only shows real figures.
CREATE OR REPLACE VIEW property_latest_valuation AS
SELECT DISTINCT ON (v.property_id)
       v.property_id, v.id AS valuation_id, v.method, v.estimate_low, v.estimate_mid, v.estimate_high,
       v.confidence, v.verdict, v.valued_at
FROM valuations v
WHERE v.status = 'valued'
ORDER BY v.property_id, v.valued_at DESC;

-- Comparable search from a point, or by district name when there is no point ---------------
-- Returns every attribute the engine needs. The engine does its own filtering, adjustment
-- and weighting; this function only narrows the candidates cheaply with the GiST index.
-- distance_m is NULL when the match was made by district name.
CREATE OR REPLACE FUNCTION search_comparables(
    p_location       geography,                      -- NULL = match by district name
    p_district       text,
    p_property_type  text,
    p_listing_types  text[],
    p_size_sqm       numeric,
    p_size_tolerance numeric          DEFAULT 0.40,
    p_radius_m       double precision DEFAULT 3000,
    p_since          date             DEFAULT (current_date - 1095),
    p_limit          integer          DEFAULT 80
)
RETURNS TABLE (comparable_id uuid, source text, listing_type text, address text, property_type text,
               size_sqm numeric, bedrooms smallint, bathrooms smallint, floor smallint, elevator boolean,
               parking boolean, year_built smallint, renovated_year smallint, condition text, energy_class text,
               price numeric, observed_on date, verified boolean, distance_m double precision)
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT c.id, c.source, c.listing_type, c.address, c.property_type, c.size_sqm, c.bedrooms, c.bathrooms,
           c.floor, c.elevator, c.parking, c.year_built, c.renovated_year, c.condition, c.energy_class,
           c.price, c.observed_on, c.verified,
           CASE WHEN p_location IS NULL THEN NULL
                ELSE round(ST_Distance(c.location, p_location)::numeric, 1)::double precision END
    FROM comparables c
    LEFT JOIN districts d ON d.id = c.district_id
    WHERE c.property_type = p_property_type
      AND c.listing_type = ANY (p_listing_types)
      AND c.size_sqm BETWEEN p_size_sqm * (1 - p_size_tolerance) AND p_size_sqm * (1 + p_size_tolerance)
      AND c.observed_on >= p_since
      AND CASE WHEN p_location IS NOT NULL THEN ST_DWithin(c.location, p_location, p_radius_m)
               ELSE p_district IS NOT NULL AND lower(d.name) = lower(p_district) END
    ORDER BY CASE WHEN p_location IS NULL THEN 0 ELSE ST_Distance(c.location, p_location) END, c.observed_on DESC
    LIMIT p_limit
$$;

-- Facilities around a point, for the location score --------------------------------------
CREATE OR REPLACE FUNCTION location_summary_at(
    p_location geography,
    p_radius_m double precision DEFAULT 1000
)
RETURNS TABLE (category text, places bigint, nearest_m double precision)
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT l.category, count(*), round(min(ST_Distance(l.location, p_location))::numeric, 1)::double precision
    FROM location_data l
    WHERE ST_DWithin(l.location, p_location, p_radius_m)
    GROUP BY l.category
    ORDER BY l.category
$$;
