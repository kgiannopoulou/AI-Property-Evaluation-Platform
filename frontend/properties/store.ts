"use client";

import { analyzeProperty } from "@/analysis/sample-engine";
import type { AnalysisResult } from "@/analysis/types";
import { createLocalStore, useLocalStore } from "@/lib/local-store";
import { preferencesStore } from "@/lib/session";
import { deleteAiInspection } from "@/inspection/api";
import { SAMPLE_PROPERTIES } from "./sample-data";
import { normalizeProperty } from "./submission";
import type { Property, PropertyInput } from "./types";

const EMPTY_PROPERTIES: Property[] = [];
const EMPTY_ANALYSES: Record<string, AnalysisResult> = {};

export const propertiesStore = createLocalStore<Property[]>(
  "pi.properties.v1",
  () => SAMPLE_PROPERTIES,
  // Records saved before Phase 5 lack the submission fields; fill neutral defaults.
  (stored) => (Array.isArray(stored) ? stored.map(normalizeProperty) : SAMPLE_PROPERTIES),
);
export const analysesStore = createLocalStore<Record<string, AnalysisResult>>("pi.analyses.v1", () =>
  Object.fromEntries(SAMPLE_PROPERTIES.map((p) => [p.id, analyzeProperty(p, new Date(p.createdAt))])),
);

export function useProperties(): Property[] {
  return useLocalStore(propertiesStore, EMPTY_PROPERTIES);
}

export function useAnalyses(): Record<string, AnalysisResult> {
  return useLocalStore(analysesStore, EMPTY_ANALYSES);
}

function newId(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `p-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

export function createProperty(input: PropertyInput): Property {
  const property: Property = { ...input, id: newId(), createdAt: new Date().toISOString(), isSample: false };
  propertiesStore.set((prev) => [property, ...prev]);
  return property;
}

export function deleteProperty(id: string): void {
  propertiesStore.set((prev) => prev.filter((p) => p.id !== id));
  deleteAiInspection(id);
  analysesStore.set((prev) => {
    const next = { ...prev };
    delete next[id];
    return next;
  });
}

/** Runs the sample engine and stores the result. */
export function runAnalysis(property: Property): AnalysisResult {
  const prefs = preferencesStore.get();
  const result = analyzeProperty(property, new Date(), {
    loanToValue: prefs.loanToValuePct / 100,
    annualRate: prefs.mortgageRatePct / 100,
    years: prefs.mortgageYears,
  });
  analysesStore.set((prev) => ({ ...prev, [property.id]: result }));
  return result;
}

export function resetDemoData(): void {
  propertiesStore.reset();
  analysesStore.reset();
}
