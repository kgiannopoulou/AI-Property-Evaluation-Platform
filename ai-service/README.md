# AI service

TypeScript (Node.js 24, no build step) service that examines property photographs with a vision model and
returns structured findings to the Java backend. The browser never calls it, so the AI provider key stays here.

## Workflow

```
User uploads photos ──► backend (Spring Boot)
                          │ stores each photo, gets a storage key
                          ▼
                        ai-service  POST /v1/inspections  {propertyId, photos:[{photoId, storageKey, area}], context}
                          │ 1. reads each photo from storage (local directory or Supabase Storage)
                          │ 2. checks the real file type (magic bytes) and size (≤ 5 MB)
                          │ 3. groups photos by area into requests (≤ 12 photos / 18 MB each)
                          │ 4. Claude vision model → findings as schema-validated JSON
                          │ 5. safety rules, photo-reference checks, repair-cost lookup
                          ▼
                        backend ──► website: findings shown on the report's Inspection tab
```

## What it looks for

Possible mould or dampness · water stains · wall and ceiling cracks · damaged flooring · old or damaged windows ·
visible plumbing concerns · visible electrical concerns · kitchen condition · bathroom condition · missing
appliances · missing furniture · signs of poor maintenance · general interior condition.

The request can include the owner's declared appliance checklist. A missing item is only reported when the photos
of the area where it belongs show it is not there; items in areas that were not photographed are never reported
as missing.

## Every finding

| Field | Meaning |
| --- | --- |
| `description`, `title` | What is visible and where |
| `photoIds` | Photograph references: the ids of the photos that show it (unknown ids are removed; a finding with none is dropped) |
| `severity` | `info`, `minor`, `moderate`, `major`, `critical` (same scale as the `inspections` table) |
| `confidence` | 0–1 |
| `assessment` | `observed` (the photo plainly shows it) or `potential-concern` (needs a professional to confirm) |
| `limitation` | What a photograph cannot establish for this finding |
| `recommendation` | The practical next step: who to ask, what to test |
| `estimatedCost` / `costNote` | A cost range only where local cost data exists; otherwise a note saying so |

### Photographs are not a diagnosis

Photos cannot establish hidden structural damage, electrical safety or the extent of moisture. The prompt says so,
and the code enforces it regardless of what the model returns (`src/defect-detection`):

- mould/damp, water stains, cracks, plumbing and electrical findings are **always** `potential-concern`, with a fixed
  limitation note;
- wording that states a diagnosis ("confirmed", "is structural", "active leak", ...) is rewritten as hedged wording,
  and titles start with "Possible:";
- confidence for potential concerns is capped at 0.85.

The database enforces the same rule for stored AI findings (`database/migrations/V5__ai_inspections.sql`).

### Repair costs

The model is never asked for prices. Ranges come from `src/report-generation/repair-costs.ts`, where each entry is
`verified` (a named, dated local source) or `illustrative`. The table currently holds **illustrative placeholders
only**, so by default no cost is shown and each finding says that no reliable local cost data exists. Set
`INCLUDE_ILLUSTRATIVE_COSTS=true` to show them (labelled as illustrative), or replace entries with verified data.

## Model call

- Official `@anthropic-ai/sdk`, model `claude-opus-5-5` (override with `AI_VISION_MODEL`), effort `high`.
- Structured output: the response must match a Zod schema (`src/vision/schema.ts`), parsed by the SDK.
- The system prompt is stable and cached between requests.
- Server-side fallback (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`): if a safety classifier
  declines, the API retries on a suitable model within the same call; `servedBy` in the report says which model
  answered.
- A refusal, a truncated answer or a provider error is returned as an error (502/503), never as an empty or
  made-up report.

## Running

```bash
npm install
npm run dev      # http://localhost:8090, reads ../.env
npm run typecheck
npm test         # uses a fake vision model and a temporary photo directory: no API calls, no cost
```

Environment (see the root `.env.example`): `ANTHROPIC_API_KEY`, `AI_SERVICE_TOKEN` (shared secret the backend sends;
required unless `NODE_ENV` is `development` or `test`), `PHOTO_STORAGE` (`local` or `supabase`), `PHOTO_DIR`,
`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_BUCKET`, `INCLUDE_ILLUSTRATIVE_COSTS`.

## API

| Method | Path | |
| --- | --- | --- |
| GET | `/health` | Public liveness check |
| GET | `/v1/capabilities` | Categories (and which are potential-concern only), photo areas, limits, model |
| POST | `/v1/inspections` | Run an inspection; `Authorization: Bearer <AI_SERVICE_TOKEN>` |

Errors: 400 invalid request, 401 missing token, 422 no photo could be read, 502 the model declined or the provider
rejected the request, 503 try again later.
