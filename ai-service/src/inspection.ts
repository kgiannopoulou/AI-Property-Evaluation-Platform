import { CHECKLIST_ITEMS, type DeclaredChecklist } from "./equipment-detection/index.ts";
import { batchPhotos, loadPhotos, type PhotoRef } from "./image-analysis/index.ts";
import { buildReport, type InspectionReport } from "./report-generation/index.ts";
import type { PhotoStore } from "./storage/index.ts";
import type { InspectionContext } from "./vision/prompt.ts";
import type { VisionModel, VisionResult } from "./vision/claude.ts";

/**
 * The inspection workflow:
 *   photos (storage keys) → load from storage → check type and size → batch
 *   → vision model → safety rules, photo-reference checks, costs → report.
 */
export type InspectionRequest = {
  propertyId: string;
  photos: PhotoRef[];
  context?: InspectionContext;
};

export type InspectionDeps = {
  store: PhotoStore;
  vision: VisionModel;
  includeIllustrativeCosts: boolean;
  /** Batches analysed at the same time. */
  concurrency?: number;
};

export class NoUsablePhotosError extends Error {
  readonly skipped: { photoId: string; reason: string }[];
  constructor(skipped: { photoId: string; reason: string }[]) {
    super("None of the photographs could be analysed");
    this.skipped = skipped;
  }
}

export async function runInspection(req: InspectionRequest, deps: InspectionDeps): Promise<InspectionReport> {
  const { loaded, skipped } = await loadPhotos(req.photos, deps.store);
  if (!loaded.length) throw new NoUsablePhotosError(skipped);

  const batches = batchPhotos(loaded);
  const results: VisionResult[] = new Array(batches.length);
  const concurrency = Math.max(1, deps.concurrency ?? 2);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(concurrency, batches.length) }, async () => {
      while (next < batches.length) {
        const i = next++;
        results[i] = await deps.vision.analyse(batches[i], req.context ?? {}, i + 1, batches.length);
      }
    }),
  );

  return buildReport({
    propertyId: req.propertyId,
    model: deps.vision.id,
    submitted: req.photos.length,
    analysedIds: new Set(loaded.map((p) => p.photoId)),
    skipped,
    results,
    includeIllustrativeCosts: deps.includeIllustrativeCosts,
  });
}

/** Accepts only known checklist items and short status strings from the caller. */
export function parseContext(raw: unknown): InspectionContext | undefined {
  if (typeof raw !== "object" || raw === null) return undefined;
  const r = raw as Record<string, unknown>;
  const context: InspectionContext = {};
  if (r.listingType === "sale" || r.listingType === "rent") context.listingType = r.listingType;
  if (typeof r.propertyType === "string" && /^[a-z-]{1,30}$/.test(r.propertyType)) context.propertyType = r.propertyType;
  if (typeof r.declaredChecklist === "object" && r.declaredChecklist !== null) {
    const declared: DeclaredChecklist = {};
    for (const item of CHECKLIST_ITEMS) {
      const v = (r.declaredChecklist as Record<string, unknown>)[item];
      if (typeof v === "string" && /^[a-z-]{1,20}$/.test(v)) declared[item] = v;
    }
    context.declaredChecklist = declared;
  }
  return context;
}
