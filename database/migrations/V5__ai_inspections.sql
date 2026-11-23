-- V5: AI image analysis (AI Home Inspector).
--
-- One ai_inspection_runs row per analysis (model, photo counts, area ratings),
-- and one inspections row per finding, shaped like the ai-service report
-- (ai-service/src/report-generation): photo references, severity, confidence,
-- observed vs potential concern, recommendation and an optional cost range.

CREATE TABLE ai_inspection_runs (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id       uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    requested_by      uuid        REFERENCES users (id) ON DELETE SET NULL,
    model             text        NOT NULL,                 -- model requested
    served_by         text[]      NOT NULL DEFAULT '{}',    -- model(s) that answered (differs after a fallback)
    photos_submitted  smallint    NOT NULL CHECK (photos_submitted BETWEEN 1 AND 24),
    photos_analysed   smallint    NOT NULL CHECK (photos_analysed BETWEEN 0 AND 24),
    skipped_photos    jsonb       NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(skipped_photos) = 'array'),
    area_conditions   jsonb       NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(area_conditions) = 'array'),
    discarded_findings smallint   NOT NULL DEFAULT 0 CHECK (discarded_findings >= 0),
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (photos_analysed <= photos_submitted)
);
CREATE INDEX ai_inspection_runs_property_idx ON ai_inspection_runs (property_id, created_at DESC);
CREATE INDEX ai_inspection_runs_requested_by_idx ON ai_inspection_runs (requested_by);

-- Findings: the AI categories join the manual ones.
ALTER TABLE inspections DROP CONSTRAINT inspections_category_check;
ALTER TABLE inspections ADD CONSTRAINT inspections_category_check CHECK (category IN (
    -- manual / survey
    'damp', 'mould', 'cracks', 'structural', 'roof', 'windows', 'electrical', 'plumbing', 'heating',
    'finishes', 'flooring', 'insulation', 'safety', 'other',
    -- AI vision
    'mould-damp', 'water-stain', 'crack', 'kitchen-condition', 'bathroom-condition', 'missing-appliance',
    'missing-furniture', 'poor-maintenance', 'interior-condition'));

ALTER TABLE inspections
    ADD COLUMN run_id            uuid    REFERENCES ai_inspection_runs (id) ON DELETE CASCADE,
    ADD COLUMN related_image_ids uuid[]  NOT NULL DEFAULT '{}',  -- further photos showing the same finding
    ADD COLUMN assessment        text    NOT NULL DEFAULT 'observed' CHECK (assessment IN ('observed', 'potential-concern')),
    ADD COLUMN limitation        text,
    ADD COLUMN recommendation    text,
    ADD COLUMN cost_low          numeric(12, 2) CHECK (cost_low IS NULL OR cost_low >= 0),
    ADD COLUMN cost_high         numeric(12, 2),
    ADD COLUMN cost_basis        text    CHECK (cost_basis IN ('verified', 'illustrative')),
    ADD COLUMN cost_source       text,
    ADD CONSTRAINT inspections_cost_range CHECK (
        (cost_low IS NULL AND cost_high IS NULL AND cost_basis IS NULL)
        OR (cost_low IS NOT NULL AND cost_high IS NOT NULL AND cost_basis IS NOT NULL AND cost_low <= cost_high)),
    ADD CONSTRAINT inspections_ai_findings_complete CHECK (
        source <> 'ai-vision' OR (run_id IS NOT NULL AND image_id IS NOT NULL AND recommendation IS NOT NULL)),
    -- Photographs cannot establish hidden structural damage, electrical safety or the extent of
    -- moisture: AI findings in those categories can only be potential concerns.
    ADD CONSTRAINT inspections_ai_hidden_risks_are_concerns CHECK (
        source <> 'ai-vision'
        OR category NOT IN ('mould-damp', 'water-stain', 'crack', 'plumbing', 'electrical', 'damp', 'mould',
                            'cracks', 'structural', 'safety')
        OR assessment = 'potential-concern');

CREATE INDEX inspections_run_idx ON inspections (run_id) WHERE run_id IS NOT NULL;
