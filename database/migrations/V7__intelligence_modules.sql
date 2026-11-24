-- V7: intelligence modules (Phase 8).
--
-- Environmental risk layers for Location Intelligence, the building details Building Analysis
-- reads, the document fields and types Document Checker reads, and stored Floor Plan Reader
-- results. A risk layer records the area it COVERS, so "outside every mapped zone" (assessed,
-- no zone) can be told apart from "no layer covers this point" (not assessed).

-- Environmental risk layers and zones -------------------------------------------------------
CREATE TABLE environmental_risk_layers (
    id            serial PRIMARY KEY,
    risk_type     text        NOT NULL CHECK (risk_type IN (
                      'flood', 'seismic', 'landslide', 'wildfire', 'noise', 'air-quality', 'industrial', 'coastal-erosion')),
    name          text        NOT NULL,
    source        text        NOT NULL,                 -- e.g. 'flood-risk-management-plan', 'manual'
    coverage      geography(MultiPolygon, 4326) NOT NULL, -- where the layer was assessed
    published_on  date,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (risk_type, source, name)
);
CREATE INDEX environmental_risk_layers_coverage_gix ON environmental_risk_layers USING gist (coverage);

CREATE TABLE environmental_risk_zones (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    layer_id     integer     NOT NULL REFERENCES environmental_risk_layers (id) ON DELETE CASCADE,
    level        text        NOT NULL CHECK (level IN ('low', 'medium', 'high', 'very-high')),
    description  text,
    area         geography(MultiPolygon, 4326) NOT NULL,
    attributes   jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(attributes) = 'object')
);
CREATE INDEX environmental_risk_zones_area_gix ON environmental_risk_zones USING gist (area);
CREATE INDEX environmental_risk_zones_layer_idx ON environmental_risk_zones (layer_id);

-- One row per layer: whether it covers the point and the highest zone level found there.
CREATE OR REPLACE FUNCTION environmental_risks_at(p_location geography)
RETURNS TABLE (risk_type text, layer_name text, source text, published_on date, covered boolean,
               level text, description text)
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT l.risk_type, l.name, l.source, l.published_on, ST_Covers(l.coverage, p_location), z.level, z.description
    FROM environmental_risk_layers l
    LEFT JOIN LATERAL (
        SELECT z.level, z.description
        FROM environmental_risk_zones z
        WHERE z.layer_id = l.id AND ST_Covers(z.area, p_location)
        ORDER BY array_position(ARRAY['low', 'medium', 'high', 'very-high'], z.level) DESC
        LIMIT 1
    ) z ON true
    ORDER BY l.risk_type, l.name
$$;

-- Buildings: details Building Analysis uses ------------------------------------------------
ALTER TABLE buildings
    ADD COLUMN elevator_installed_year  smallint CHECK (elevator_installed_year IS NULL OR elevator_installed_year >= 1900),
    ADD COLUMN elevator_last_inspection date,
    ADD COLUMN roof_waterproofing_year  smallint CHECK (roof_waterproofing_year IS NULL OR roof_waterproofing_year >= 1900),
    ADD COLUMN external_insulation      boolean,
    ADD COLUMN windows                  text CHECK (windows IS NULL OR windows IN ('single', 'double', 'triple', 'unknown')),
    ADD COLUMN pilotis                  boolean,
    ADD COLUMN reserve_fund             numeric(12, 2) CHECK (reserve_fund IS NULL OR reserve_fund >= 0),
    ADD COLUMN planned_levy_per_unit    numeric(12, 2) CHECK (planned_levy_per_unit IS NULL OR planned_levy_per_unit >= 0);

-- Documents: the Greek transaction documents and the fields read from them ---------------------
ALTER TABLE documents DROP CONSTRAINT documents_document_type_check;
ALTER TABLE documents ADD CONSTRAINT documents_document_type_check CHECK (document_type IN (
    'title-deed', 'energy-certificate', 'building-permit', 'floor-plan', 'cadastral-extract', 'tax-certificate',
    'utility-bill', 'lease', 'purchase-contract', 'common-charges-statement', 'other',
    -- V7
    'engineer-certificate', 'enfia-certificate', 'municipal-certificate', 'encumbrance-certificate',
    'common-charges-certificate', 'legalisation-declaration'));
ALTER TABLE documents
    ADD COLUMN issued_on        date,
    ADD COLUMN document_number  text,
    ADD COLUMN fields           jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(fields) = 'object'), -- size, owners, address…
    ADD COLUMN fields_source    text CHECK (fields_source IS NULL OR fields_source IN ('user', 'extracted', 'verified'));

-- Floor plan readings ------------------------------------------------------------------------
CREATE TABLE floor_plan_readings (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id     uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    document_id     uuid        REFERENCES documents (id) ON DELETE SET NULL,
    model           text        NOT NULL,
    served_by       text        NOT NULL,
    legibility      text        NOT NULL CHECK (legibility IN ('clear', 'partly-legible', 'illegible')),
    rooms           jsonb       NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(rooms) = 'array'),
    rooms_area_sqm  numeric(8, 2) CHECK (rooms_area_sqm IS NULL OR rooms_area_sqm >= 0),
    printed_total   numeric(8, 2) CHECK (printed_total IS NULL OR printed_total > 0),
    checks          jsonb       NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(checks) = 'array'),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX floor_plan_readings_property_idx ON floor_plan_readings (property_id, created_at DESC);
CREATE INDEX floor_plan_readings_document_idx ON floor_plan_readings (document_id);
