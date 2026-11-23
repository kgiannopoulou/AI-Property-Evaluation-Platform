import type { FindingCategory, Severity } from "../defect-detection/index.ts";

/**
 * Repair-cost ranges, by finding category and severity. The vision model is
 * never asked for prices: a cost range is attached only from this table.
 *
 * Each entry says how reliable it is:
 *   "verified"     — from a named, dated local source (quotes, a cost guide);
 *   "illustrative" — round placeholder values for development and demos.
 *
 * Illustrative ranges are only returned when INCLUDE_ILLUSTRATIVE_COSTS=true,
 * and are labelled as such. Replace them with verified data before relying on
 * the figures: set basis "verified" and fill in `source` and `asOf`.
 */
export type CostBasis = "verified" | "illustrative";

export type CostEntry = {
  low: number;
  high: number;
  currency: "EUR";
  basis: CostBasis;
  source: string;
  region: string;
  asOf: string;
  /** What the range covers, shown next to it. */
  covers: string;
};

type Table = Partial<Record<FindingCategory, Partial<Record<Severity, Omit<CostEntry, "currency" | "basis" | "source" | "region" | "asOf">>>>>;

const ILLUSTRATIVE_SOURCE = "Illustrative placeholder values for development; not verified local cost data.";

const TABLE: Table = {
  "mould-damp": {
    minor: { low: 150, high: 400, covers: "Cleaning, anti-mould treatment and repainting of the affected surface" },
    moderate: { low: 400, high: 1500, covers: "Moisture survey, treatment and replastering of one wall or ceiling" },
    major: { low: 1500, high: 5000, covers: "Locating and fixing the moisture source, waterproofing, making good" },
  },
  "water-stain": {
    minor: { low: 100, high: 300, covers: "Stain-blocking primer and repainting" },
    moderate: { low: 300, high: 1200, covers: "Leak investigation and local repair, then making good" },
  },
  crack: {
    minor: { low: 100, high: 350, covers: "Filling and repainting hairline cracks" },
    moderate: { low: 300, high: 900, covers: "Engineer's visit and repair of plaster cracks" },
  },
  flooring: {
    minor: { low: 100, high: 400, covers: "Repairing or replacing a few tiles or boards" },
    moderate: { low: 600, high: 1800, covers: "Replacing flooring in one room (about 15-25 m²)" },
  },
  windows: {
    minor: { low: 80, high: 250, covers: "Seals, handles or hinges on one window" },
    moderate: { low: 450, high: 900, covers: "Replacing one window with a double-glazed unit" },
  },
  plumbing: {
    minor: { low: 80, high: 250, covers: "Plumber's visit for a tap, trap or visible joint" },
    moderate: { low: 250, high: 900, covers: "Replacing visible pipework or a fixture" },
  },
  electrical: {
    minor: { low: 80, high: 250, covers: "Electrician's visit for sockets, switches or a fitting" },
    moderate: { low: 200, high: 600, covers: "Electrical inspection and test certificate" },
  },
  "kitchen-condition": {
    moderate: { low: 1500, high: 4000, covers: "Refreshing units: doors, worktop, sink and tap" },
    major: { low: 4000, high: 9000, covers: "New mid-range kitchen" },
  },
  "bathroom-condition": {
    moderate: { low: 1200, high: 3000, covers: "Partial refurbishment: fixtures, sealant, some tiling" },
    major: { low: 3500, high: 8000, covers: "Full bathroom renovation" },
  },
  "missing-appliance": {
    moderate: { low: 350, high: 900, covers: "One mid-range appliance, delivered and installed" },
  },
};

export function lookupRepairCost(category: FindingCategory, severity: Severity): CostEntry | null {
  const entry = TABLE[category]?.[severity];
  if (!entry) return null;
  return {
    ...entry,
    currency: "EUR",
    basis: "illustrative",
    source: ILLUSTRATIVE_SOURCE,
    region: "Greece (Attica)",
    asOf: "2026-11",
  };
}
