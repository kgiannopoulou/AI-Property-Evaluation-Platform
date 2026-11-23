/**
 * What the vision model looks for, and the rules that keep its output honest.
 *
 * Photographs show surfaces. They cannot establish hidden structural damage,
 * electrical safety or how far moisture has spread, so findings in those
 * categories are always reported as potential concerns — never as confirmed
 * diagnoses — whatever the model returns (see applySafetyRules).
 */

export const FINDING_CATEGORIES = [
  "mould-damp",
  "water-stain",
  "crack",
  "flooring",
  "windows",
  "plumbing",
  "electrical",
  "kitchen-condition",
  "bathroom-condition",
  "missing-appliance",
  "missing-furniture",
  "poor-maintenance",
  "interior-condition",
] as const;
export type FindingCategory = (typeof FINDING_CATEGORIES)[number];

export const CATEGORY_LABELS: Record<FindingCategory, string> = {
  "mould-damp": "Possible mould or dampness",
  "water-stain": "Water stains",
  crack: "Wall and ceiling cracks",
  flooring: "Damaged flooring",
  windows: "Old or damaged windows",
  plumbing: "Visible plumbing concerns",
  electrical: "Visible electrical concerns",
  "kitchen-condition": "Kitchen condition",
  "bathroom-condition": "Bathroom condition",
  "missing-appliance": "Missing appliances",
  "missing-furniture": "Missing furniture",
  "poor-maintenance": "Signs of poor maintenance",
  "interior-condition": "General interior condition",
};

/** Same scale as the database `inspections.severity` column. */
export const SEVERITIES = ["info", "minor", "moderate", "major", "critical"] as const;
export type Severity = (typeof SEVERITIES)[number];

/**
 * "observed": the photo itself shows the issue (a broken tile, a missing oven).
 * "potential-concern": the photo shows a sign that needs a professional to
 * confirm (a crack that may or may not be structural, a stain that may be an
 * old or an active leak).
 */
export const ASSESSMENTS = ["observed", "potential-concern"] as const;
export type Assessment = (typeof ASSESSMENTS)[number];

/** Categories where a photograph can only ever raise a concern. */
export const POTENTIAL_CONCERN_ONLY: ReadonlySet<FindingCategory> = new Set([
  "mould-damp",
  "water-stain",
  "crack",
  "plumbing",
  "electrical",
]);

/** Fixed wording appended to those findings, so the limitation is never left to the model. */
export const LIMITATION_NOTES: Partial<Record<FindingCategory, string>> = {
  "mould-damp":
    "Photos cannot show how far moisture has spread or whether it is active; a moisture survey is needed to confirm.",
  "water-stain":
    "A stain cannot show whether a leak is old or still active, or how far water has spread; have it inspected.",
  crack:
    "Photos cannot establish whether a crack is structural; only an on-site structural engineer can assess it.",
  plumbing: "Hidden pipework cannot be assessed from photos; a plumber should inspect and pressure-test it.",
  electrical:
    "Electrical safety cannot be established from photos; a licensed electrician should test the installation.",
};

/**
 * Certainty a photograph cannot support, rewritten as hedged wording for
 * potential concerns. The prompt already asks for this; this is the backstop.
 */
const CERTAINTY_WORDS = /\b(?:confirmed|definitely|certainly|clearly|undoubtedly)\s*/gi;
const DIAGNOSES: [RegExp, string][] = [
  [/\bis (?:a )?structural (?:failure|damage|problem)\b/gi, "may indicate a structural problem"],
  [/\bstructural (?:failure|damage)\b/gi, "possible structural problem"],
  [/\bis structural\b/gi, "may be structural"],
  [/\bis (?:unsafe|dangerous)\b/gi, "may be unsafe"],
  [/\bis safe\b/gi, "cannot be confirmed as safe from photos"],
  [/\b(?:an )?active leak\b/gi, "a possible leak"],
  [/\bis mould\b/gi, "may be mould"],
];

function hedge(text: string): string {
  const out = DIAGNOSES.reduce((t, [pattern, replacement]) => t.replace(pattern, replacement), text.replace(CERTAINTY_WORDS, ""));
  return out.charAt(0).toUpperCase() + out.slice(1);
}

/** Titles of potential concerns say so up front. */
function hedgeTitle(title: string): string {
  const t = hedge(title);
  return /^(possible|potential|signs? of|suspected)\b/i.test(t) ? t : `Possible: ${t.charAt(0).toLowerCase()}${t.slice(1)}`;
}

export type ModelFinding = {
  photoIds: string[];
  category: FindingCategory;
  title: string;
  description: string;
  severity: Severity;
  /** 0–1. */
  confidence: number;
  assessment: Assessment;
  recommendation: string;
};

export type SafeFinding = ModelFinding & {
  /** Present on potential concerns: what a photograph cannot tell you. */
  limitation: string | null;
};

/**
 * Enforces the inspection rules on whatever the model returned:
 *  - hidden-risk categories are always potential concerns, with the fixed limitation note;
 *  - certainty and diagnoses in a potential concern are rewritten as hedged wording;
 *  - confidence is clamped to 0–1 (and to 0.85 for potential concerns — a photo is never proof).
 */
export function applySafetyRules(f: ModelFinding): SafeFinding {
  const hiddenRisk = POTENTIAL_CONCERN_ONLY.has(f.category);
  const assessment: Assessment = hiddenRisk ? "potential-concern" : f.assessment;
  let confidence = Number.isFinite(f.confidence) ? Math.min(1, Math.max(0, f.confidence)) : 0;
  if (assessment === "potential-concern") confidence = Math.min(confidence, 0.85);

  const concern = assessment === "potential-concern";

  const limitation =
    LIMITATION_NOTES[f.category] ??
    (assessment === "potential-concern"
      ? "Based on photographs only; confirm on site before relying on it."
      : null);

  return {
    ...f,
    title: concern ? hedgeTitle(f.title.trim()) : f.title.trim(),
    description: concern ? hedge(f.description.trim()) : f.description.trim(),
    recommendation: f.recommendation.trim(),
    assessment,
    confidence: Math.round(confidence * 100) / 100,
    limitation,
  };
}
