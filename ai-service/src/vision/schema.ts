import { z } from "zod";
import { ASSESSMENTS, FINDING_CATEGORIES, SEVERITIES } from "../defect-detection/index.ts";
import { PHOTO_AREAS } from "../equipment-detection/index.ts";

/**
 * The JSON the model must return for one batch of photos. Sent to the API as
 * a structured-output schema, so the response is guaranteed to parse; value
 * ranges (confidence 0–1, known photo ids) are enforced afterwards in code.
 */
export const AREA_CONDITIONS = ["poor", "fair", "good", "excellent", "not-assessable"] as const;
export type AreaConditionRating = (typeof AREA_CONDITIONS)[number];

export const BatchResultSchema = z.object({
  findings: z.array(
    z.object({
      photo_ids: z.array(z.string()).describe("Ids of the photographs that show this finding, from the list given."),
      category: z.enum(FINDING_CATEGORIES),
      title: z.string().describe("Short headline, max 80 characters."),
      description: z.string().describe("What is visible and where in the photo. Plain language, 1-3 sentences."),
      severity: z.enum(SEVERITIES),
      confidence: z.number().describe("0 to 1: how sure you are that the photo shows what you describe."),
      assessment: z.enum(ASSESSMENTS),
      recommendation: z.string().describe("Next step: who should check or repair it, and what to ask."),
    }),
  ),
  area_conditions: z.array(
    z.object({
      area: z.enum(PHOTO_AREAS),
      condition: z.enum(AREA_CONDITIONS),
      summary: z.string().describe("One or two sentences on the overall condition visible in this area."),
    }),
  ),
  unusable_photos: z.array(
    z.object({
      photo_id: z.string(),
      reason: z.string().describe("e.g. too dark, blurred, not a photo of the property."),
    }),
  ),
});
export type BatchResult = z.infer<typeof BatchResultSchema>;
