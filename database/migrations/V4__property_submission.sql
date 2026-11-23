-- V4: property submission system.
--
-- Stores everything the "Analyze a property" form collects
-- (frontend/properties/types.ts, validated by frontend/properties/submission.ts):
--
--   purchase or rental        properties.listing_type (asking_price = monthly rent for rentals)
--   construction type         properties.construction_type
--   cooling                   properties.cooling
--   energy certificate        properties.energy_certificate_status / _number
--   appliance checklist       appliance_checklist (one row per item, statuses checked per item)
--   furniture and appliances  appliance_checklist + equipment (other items)
--   building expenses         buildings.monthly_common_charges, common_charges_include_heating, planned_works
--   included facilities       property_features (category 'facility'), properties.other_facilities
--   photos per area           property_images.area

-- Properties --------------------------------------------------------------------
ALTER TABLE properties
    ADD COLUMN listing_type              text NOT NULL DEFAULT 'sale' CHECK (listing_type IN ('sale', 'rent')),
    ADD COLUMN construction_type         text NOT NULL DEFAULT 'unknown'
        CHECK (construction_type IN ('reinforced-concrete', 'masonry', 'steel', 'timber', 'mixed', 'unknown')),
    ADD COLUMN cooling                   text NOT NULL DEFAULT 'none'
        CHECK (cooling IN ('none', 'split-units', 'central-ac', 'heat-pump', 'ceiling-fans')),
    ADD COLUMN energy_certificate_status text NOT NULL DEFAULT 'unknown'
        CHECK (energy_certificate_status IN ('issued', 'pending', 'none', 'unknown')),
    ADD COLUMN energy_certificate_number text CHECK (char_length(energy_certificate_number) <= 40),
    ADD COLUMN other_facilities          text CHECK (char_length(other_facilities) <= 500),
    ADD CONSTRAINT properties_certificate_number_needs_issued
        CHECK (energy_certificate_number IS NULL OR energy_certificate_status = 'issued');

COMMENT ON COLUMN properties.asking_price IS 'Asking sale price, or asking monthly rent when listing_type = ''rent'' (EUR).';

-- Same ranges the form enforces: €10k–€20m for a sale, €100–€50k a month for a rental.
ALTER TABLE properties
    ADD CONSTRAINT properties_asking_price_range CHECK (
        (listing_type = 'sale' AND asking_price BETWEEN 10000 AND 20000000)
        OR (listing_type = 'rent' AND asking_price BETWEEN 100 AND 50000)
    );

CREATE INDEX properties_listing_idx ON properties (listing_type, property_type);

-- Building expenses ---------------------------------------------------------------
ALTER TABLE buildings
    ADD COLUMN common_charges_include_heating boolean NOT NULL DEFAULT false,
    ADD COLUMN planned_works                  text CHECK (char_length(planned_works) <= 500);

-- Appliance checklist ---------------------------------------------------------------
-- Each item has its own set of allowed statuses:
--   appliances  working | damaged | missing | unknown   ("included and working" = working)
--   heating     available | unavailable | unknown
--   furniture   included | partial | not-included
CREATE TABLE appliance_checklist (
    property_id  uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    item         text        NOT NULL CHECK (item IN (
                     'refrigerator', 'washing-machine', 'dishwasher', 'oven-stove', 'air-conditioning',
                     'heating', 'furniture')),
    status       text        NOT NULL,
    notes        text        CHECK (char_length(notes) <= 500),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (property_id, item),
    CONSTRAINT appliance_checklist_status_per_item CHECK (
        (item IN ('refrigerator', 'washing-machine', 'dishwasher', 'oven-stove', 'air-conditioning')
            AND status IN ('working', 'damaged', 'missing', 'unknown'))
        OR (item = 'heating'   AND status IN ('available', 'unavailable', 'unknown'))
        OR (item = 'furniture' AND status IN ('included', 'partial', 'not-included'))
    )
);

CREATE TRIGGER appliance_checklist_set_updated_at BEFORE UPDATE ON appliance_checklist
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Photos per area -----------------------------------------------------------------------
ALTER TABLE property_images
    ADD COLUMN area text NOT NULL DEFAULT 'other' CHECK (area IN (
        'kitchen', 'bathroom', 'bedroom', 'living-room', 'exterior', 'building-entrance', 'other'));

-- Existing rows: derive the area from the free-text room where it is obvious.
UPDATE property_images SET area = CASE
        WHEN room ILIKE '%kitchen%'                       THEN 'kitchen'
        WHEN room ILIKE '%bath%' OR room ILIKE '%wc%'     THEN 'bathroom'
        WHEN room ILIKE '%bedroom%'                       THEN 'bedroom'
        WHEN room ILIKE '%living%'                        THEN 'living-room'
        WHEN room ILIKE '%entrance%' OR room ILIKE '%stair%' OR room ILIKE '%lobby%' THEN 'building-entrance'
        WHEN room ILIKE '%exterior%' OR room ILIKE '%facade%' OR room ILIKE '%façade%' THEN 'exterior'
        ELSE 'other'
    END
WHERE room IS NOT NULL;

DROP INDEX property_images_property_idx;
CREATE INDEX property_images_property_area_idx ON property_images (property_id, area, position);

-- At most 8 photos per area and 24 per property (the form's limits), enforced on insert.
CREATE OR REPLACE FUNCTION property_images_limit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- Serialise concurrent uploads for the same property so the counts are exact.
    PERFORM 1 FROM properties WHERE id = NEW.property_id FOR UPDATE;
    IF (SELECT count(*) FROM property_images WHERE property_id = NEW.property_id AND area = NEW.area) >= 8 THEN
        RAISE EXCEPTION 'at most 8 photographs per area (%)', NEW.area USING ERRCODE = 'check_violation';
    END IF;
    IF (SELECT count(*) FROM property_images WHERE property_id = NEW.property_id) >= 24 THEN
        RAISE EXCEPTION 'at most 24 photographs per property' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER property_images_limit BEFORE INSERT ON property_images
    FOR EACH ROW EXECUTE FUNCTION property_images_limit();

-- One view of a submission, shaped like the frontend PropertyInput ------------------------
CREATE VIEW property_submissions AS
SELECT p.id,
       p.owner_id,
       p.listing_type,
       p.title,
       p.address,
       d.name                                   AS district,
       ST_Y(p.location::geometry)               AS lat,
       ST_X(p.location::geometry)               AS lon,
       p.property_type, p.size_sqm, p.bedrooms, p.bathrooms, p.floor,
       p.year_built, p.renovated_year, p.construction_type, p.condition,
       p.energy_certificate_status, p.energy_certificate_number, p.energy_class,
       p.heating, p.cooling, p.parking, p.storage, p.elevator, p.balcony_sqm, p.asking_price,
       b.monthly_common_charges,
       coalesce(b.common_charges_include_heating, false) AS common_charges_include_heating,
       b.planned_works,
       coalesce((SELECT jsonb_object_agg(c.item, c.status) FROM appliance_checklist c WHERE c.property_id = p.id),
                '{}')                           AS checklist,
       coalesce((SELECT array_agg(f.name ORDER BY f.name) FROM property_features f
                 WHERE f.property_id = p.id AND f.category = 'facility'), '{}') AS facilities,
       p.other_facilities,
       coalesce((SELECT jsonb_object_agg(area, n) FROM (
                     SELECT i.area, count(*) AS n FROM property_images i WHERE i.property_id = p.id GROUP BY i.area) x),
                '{}')                           AS photos_per_area,
       p.notes,
       p.created_at
FROM properties p
LEFT JOIN districts d ON d.id = p.district_id
LEFT JOIN buildings b ON b.id = p.building_id;
