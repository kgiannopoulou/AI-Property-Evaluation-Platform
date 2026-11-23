import { randomUUID } from "node:crypto";
import {
  applySafetyRules,
  CATEGORY_LABELS,
  SEVERITIES,
  type Assessment,
  type FindingCategory,
  type Severity,
} from "../defect-detection/index.ts";
import type { PhotoArea } from "../equipment-detection/index.ts";
import type { SkippedPhoto } from "../image-analysis/index.ts";
import type { AreaConditionRating, BatchResult } from "../vision/schema.ts";
import { lookupRepairCost, type CostEntry } from "./repair-costs.ts";

export const DISCLAIMER =
  "Findings are based only on the photographs supplied. They are not a survey: hidden structural damage, " +
  "electrical safety and the extent of moisture cannot be established from photos, so those findings are " +
  "potential concerns to be checked on site by a qualified professional.";

export type Finding = {
  id: string;
  /** Photograph references: ids of the photos that show the finding. */
  photoIds: string[];
  category: FindingCategory;
  categoryLabel: string;
  title: string;
  description: string;
  severity: Severity;
  /** 0–1. */
  confidence: number;
  assessment: Assessment;
  /** What a photograph cannot establish for this finding (potential concerns). */
  limitation: string | null;
  recommendation: string;
  /** Present only where cost data exists for this kind of repair (see repair-costs.ts). */
  estimatedCost: CostEntry | null;
  costNote: string | null;
};

export type AreaCondition = { area: PhotoArea; condition: AreaConditionRating; summary: string };

export type InspectionReport = {
  propertyId: string;
  generatedAt: string;
  /** Model requested, and the model(s) that actually answered (differs if a fallback ran). */
  model: string;
  servedBy: string[];
  photos: { submitted: number; analysed: number; skipped: SkippedPhoto[] };
  findings: Finding[];
  areaConditions: AreaCondition[];
  /** Findings dropped because they referenced photos that were not in the request. */
  discardedFindings: number;
  disclaimer: string;
};

const CONDITION_RANK: Record<AreaConditionRating, number> = {
  poor: 0,
  fair: 1,
  good: 2,
  excellent: 3,
  "not-assessable": 4,
};

export type BuildOptions = {
  propertyId: string;
  model: string;
  submitted: number;
  analysedIds: Set<string>;
  skipped: SkippedPhoto[];
  results: { result: BatchResult; servedBy: string }[];
  includeIllustrativeCosts: boolean;
  now?: Date;
};

export function buildReport(o: BuildOptions): InspectionReport {
  let discarded = 0;
  const findings: Finding[] = [];
  const skipped = [...o.skipped];
  const skippedIds = new Set(skipped.map((s) => s.photoId));

  for (const { result } of o.results) {
    for (const raw of result.findings) {
      // Photo references must point at photos that were actually analysed.
      const photoIds = [...new Set(raw.photo_ids)].filter((id) => o.analysedIds.has(id));
      if (!photoIds.length) {
        discarded++;
        continue;
      }
      const safe = applySafetyRules({
        photoIds,
        category: raw.category,
        title: raw.title,
        description: raw.description,
        severity: raw.severity,
        confidence: raw.confidence,
        assessment: raw.assessment,
        recommendation: raw.recommendation,
      });
      const cost = lookupRepairCost(safe.category, safe.severity);
      const useCost = cost && (cost.basis === "verified" || o.includeIllustrativeCosts) ? cost : null;
      findings.push({
        id: randomUUID(),
        ...safe,
        categoryLabel: CATEGORY_LABELS[safe.category],
        estimatedCost: useCost,
        costNote: useCost
          ? useCost.basis === "illustrative"
            ? "Illustrative range, not verified local cost data."
            : null
          : "No reliable local cost data for this repair; ask a contractor for a quote.",
      });
    }
    for (const u of result.unusable_photos) {
      if (o.analysedIds.has(u.photo_id) && !skippedIds.has(u.photo_id)) {
        skipped.push({ photoId: u.photo_id, reason: u.reason });
        skippedIds.add(u.photo_id);
      }
    }
  }

  // Worst rating wins when an area appears in more than one batch.
  const byArea = new Map<PhotoArea, AreaCondition>();
  for (const { result } of o.results)
    for (const c of result.area_conditions) {
      const prev = byArea.get(c.area);
      if (!prev || CONDITION_RANK[c.condition] < CONDITION_RANK[prev.condition]) byArea.set(c.area, c);
    }

  findings.sort(
    (a, b) => SEVERITIES.indexOf(b.severity) - SEVERITIES.indexOf(a.severity) || b.confidence - a.confidence,
  );

  return {
    propertyId: o.propertyId,
    generatedAt: (o.now ?? new Date()).toISOString(),
    model: o.model,
    servedBy: [...new Set(o.results.map((r) => r.servedBy))],
    photos: { submitted: o.submitted, analysed: o.analysedIds.size - skipped.filter((s) => o.analysedIds.has(s.photoId)).length, skipped },
    findings,
    areaConditions: [...byArea.values()],
    discardedFindings: discarded,
    disclaimer: DISCLAIMER,
  };
}
