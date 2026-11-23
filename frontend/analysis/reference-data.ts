/**
 * ILLUSTRATIVE reference values for the Phase 3 sample engine.
 *
 * These are round, hand-picked numbers so the interface has something
 * plausible to render. They are NOT market data and must be replaced by
 * the Java valuation service backed by real transactions (Phase 4+).
 */

export type DistrictReference = {
  name: string;
  /** Illustrative sale price per m², EUR. */
  salePerSqm: number;
  /** Illustrative monthly rent per m², EUR. */
  rentPerSqm: number;
  /** 0–100 qualitative location score used by the preview score. */
  locationScore: number;
};

export const DISTRICTS: DistrictReference[] = [
  { name: "Kolonaki", salePerSqm: 6200, rentPerSqm: 17, locationScore: 92 },
  { name: "Glyfada", salePerSqm: 5100, rentPerSqm: 15, locationScore: 88 },
  { name: "Kifisia", salePerSqm: 4600, rentPerSqm: 13, locationScore: 86 },
  { name: "Marousi", salePerSqm: 3700, rentPerSqm: 12, locationScore: 80 },
  { name: "Chalandri", salePerSqm: 3600, rentPerSqm: 11.5, locationScore: 79 },
  { name: "Pagrati", salePerSqm: 3300, rentPerSqm: 12.5, locationScore: 78 },
  { name: "Nea Smyrni", salePerSqm: 3200, rentPerSqm: 11.5, locationScore: 77 },
  { name: "Kallithea", salePerSqm: 2500, rentPerSqm: 10, locationScore: 70 },
  { name: "Piraeus", salePerSqm: 2400, rentPerSqm: 10, locationScore: 68 },
  { name: "Exarcheia", salePerSqm: 2700, rentPerSqm: 12, locationScore: 66 },
  { name: "Peristeri", salePerSqm: 1800, rentPerSqm: 8, locationScore: 62 },
  { name: "Ilion", salePerSqm: 1700, rentPerSqm: 7.5, locationScore: 60 },
  { name: "Other / not listed", salePerSqm: 2200, rentPerSqm: 8.5, locationScore: 60 },
];

export function districtReference(name: string): DistrictReference {
  return DISTRICTS.find((d) => d.name === name) ?? DISTRICTS[DISTRICTS.length - 1];
}

/** Illustrative purchase-cost assumptions (Greece-style, simplified). */
export const ACQUISITION_COSTS = {
  transferTaxRate: 0.0309,
  notaryRate: 0.01,
  legalRate: 0.01,
  landRegistryRate: 0.005,
  agentRate: 0.02,
};

export const MORTGAGE_DEFAULTS = {
  loanToValue: 0.8,
  annualRate: 0.039,
  years: 25,
};

/** Renovation cost per m² for each scenario, EUR (illustrative). */
export const RENOVATION_PER_SQM = {
  basic: 180,
  medium: 420,
  premium: 780,
};

/** Illustrative replacement cost for each checklist appliance, EUR. */
export const APPLIANCE_REPLACEMENT_COST = {
  refrigerator: 650,
  "washing-machine": 500,
  dishwasher: 480,
  "oven-stove": 700,
  "air-conditioning": 850,
} as const;

/** Illustrative tenant move-in assumptions (Greece-style, simplified). */
export const TENANCY_COSTS = {
  depositMonths: 2,
  agentFeeMonths: 1,
  vatRate: 0.24,
  /** Electricity, water and internet for an average flat, EUR per m² per month. */
  utilitiesPerSqm: 1.1,
};
