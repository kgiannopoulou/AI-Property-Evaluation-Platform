"use client";

import { authHeaders, toApiError } from "@/lib/api";
import { createLocalStore, useLocalStore } from "@/lib/local-store";
import type { Property } from "@/properties/types";

/**
 * AI Home Inspector client. The browser sends the property's photos to the
 * Java backend, which stores them and asks the ai-service (vision model) to
 * examine them; the structured findings come back here.
 */

export type AiSeverity = "info" | "minor" | "moderate" | "major" | "critical";

export type AiFinding = {
  id: string;
  photoIds: string[];
  category: string;
  categoryLabel: string;
  title: string;
  description: string;
  severity: AiSeverity;
  confidence: number;
  assessment: "observed" | "potential-concern";
  limitation: string | null;
  recommendation: string;
  estimatedCost: {
    low: number;
    high: number;
    currency: "EUR";
    basis: "verified" | "illustrative";
    source: string;
    region: string;
    asOf: string;
    covers: string;
  } | null;
  costNote: string | null;
};

export type AiInspectionReport = {
  propertyId: string;
  generatedAt: string;
  model: string;
  servedBy: string[];
  photos: { submitted: number; analysed: number; skipped: { photoId: string; reason: string }[] };
  findings: AiFinding[];
  areaConditions: { area: string; condition: "poor" | "fair" | "good" | "excellent" | "not-assessable"; summary: string }[];
  discardedFindings: number;
  disclaimer: string;
};

/** Base URL of the Java backend, e.g. http://localhost:8080. Unset = AI inspection unavailable. */
export const API_URL = process.env.NEXT_PUBLIC_API_URL?.replace(/\/$/, "") ?? "";

const EMPTY: Record<string, AiInspectionReport> = {};
export const aiInspectionsStore = createLocalStore<Record<string, AiInspectionReport>>("pi.ai-inspections.v1", () => ({}));

export function useAiInspections(): Record<string, AiInspectionReport> {
  return useLocalStore(aiInspectionsStore, EMPTY);
}

export class AiInspectionError extends Error {}

/** Photo ids must be letters, digits, "-" or "_" for the API; older ids are mapped. */
function apiPhotoId(id: string, index: number): string {
  return /^[A-Za-z0-9_-]{1,64}$/.test(id) ? id : `photo-${index}`;
}

export async function runAiInspection(property: Property): Promise<AiInspectionReport> {
  if (!API_URL) throw new AiInspectionError("The AI inspection service is not connected.");
  if (property.photos.length === 0) throw new AiInspectionError("Add photographs first.");

  const form = new FormData();
  form.append("propertyId", property.id.replace(/[^A-Za-z0-9_-]/g, "-").slice(0, 64));
  form.append("listingType", property.listingType);
  form.append("propertyType", property.propertyType);
  for (const [item, status] of Object.entries(property.checklist.appliances)) form.append(`checklist.${item}`, status);
  form.append("checklist.heating", property.checklist.heating);
  form.append("checklist.furniture", property.checklist.furniture);

  const idMap = new Map<string, string>();
  await Promise.all(
    property.photos.map(async (p, i) => {
      const blob = await (await fetch(p.dataUrl)).blob();
      idMap.set(apiPhotoId(p.id, i), p.id);
      return { p, i, blob };
    }),
  ).then((items) =>
    items.forEach(({ p, i, blob }) => {
      form.append("photos", blob, p.name || `photo-${i}.jpg`);
      form.append("photoIds", apiPhotoId(p.id, i));
      form.append("areas", p.area);
    }),
  );

  let res: Response;
  try {
    res = await fetch(`${API_URL}/api/v1/inspections`, { method: "POST", body: form, headers: authHeaders(property.id) });
  } catch {
    throw new AiInspectionError("Could not reach the backend. Is it running?");
  }
  if (!res.ok) throw new AiInspectionError((await toApiError(res, "The inspection")).message);
  const report = (await res.json()) as AiInspectionReport;
  // Map photo references back to the ids used in this browser.
  const mapped: AiInspectionReport = {
    ...report,
    findings: report.findings.map((f) => ({ ...f, photoIds: f.photoIds.map((id) => idMap.get(id) ?? id) })),
    photos: {
      ...report.photos,
      skipped: report.photos.skipped.map((s) => ({ ...s, photoId: idMap.get(s.photoId) ?? s.photoId })),
    },
  };
  aiInspectionsStore.set((prev) => ({ ...prev, [property.id]: mapped }));
  return mapped;
}

export function deleteAiInspection(propertyId: string): void {
  aiInspectionsStore.set((prev) => {
    if (!(propertyId in prev)) return prev;
    const next = { ...prev };
    delete next[propertyId];
    return next;
  });
}
