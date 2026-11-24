"use client";

import { postJson } from "@/lib/api";
import { createLocalStore, useLocalStore } from "@/lib/local-store";
import type { RepairQuote, ValuationReport, ValuationRequest } from "./types";

const EMPTY_REPORTS: Record<string, ValuationReport> = {};
const EMPTY_QUOTES: Record<string, RepairQuote[]> = {};

export const valuationsStore = createLocalStore<Record<string, ValuationReport>>("pi.valuations.v1", () => ({}));
export const repairQuotesStore = createLocalStore<Record<string, RepairQuote[]>>("pi.repair-quotes.v1", () => ({}));

export function useValuations(): Record<string, ValuationReport> {
  return useLocalStore(valuationsStore, EMPTY_REPORTS);
}

export function useRepairQuotes(): Record<string, RepairQuote[]> {
  return useLocalStore(repairQuotesStore, EMPTY_QUOTES);
}

export function setRepairQuotes(propertyId: string, quotes: RepairQuote[]): void {
  repairQuotesStore.set((prev) => ({ ...prev, [propertyId]: quotes }));
}

export async function runValuation(propertyId: string, request: ValuationRequest): Promise<ValuationReport> {
  const report = await postJson<ValuationReport>("/api/v1/valuations", request, "The valuation", propertyId);
  valuationsStore.set((prev) => ({ ...prev, [propertyId]: report }));
  return report;
}
