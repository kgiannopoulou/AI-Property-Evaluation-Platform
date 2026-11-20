export type Verdict = "below-market" | "fair" | "above-market";

export type ValuationFactor = {
  label: string;
  /** Multiplicative adjustment expressed as a percentage, e.g. +6 or -12. */
  impactPct: number;
  detail: string;
};

export type Comparable = {
  id: string;
  label: string;
  district: string;
  sizeSqm: number;
  price: number;
  pricePerSqm: number;
  yearBuilt: number;
  distanceKm: number;
};

export type InspectionSeverity = "info" | "low" | "medium" | "high";

export type InspectionFinding = {
  area: string;
  finding: string;
  severity: InspectionSeverity;
  estimatedCost: number;
};

export type RenovationScenario = {
  tier: "basic" | "medium" | "premium";
  label: string;
  cost: number;
  valueAfter: number;
  scope: string[];
};

export type ScoreBreakdown = {
  overall: number;
  price: number;
  location: number;
  condition: number;
  energy: number;
};

export type AnalysisResult = {
  propertyId: string;
  generatedAt: string;
  /** Always "sample" in Phase 3; real engines will report their own id. */
  engine: "sample-v1";
  estimate: { low: number; mid: number; high: number };
  pricePerSqm: { asking: number; estimated: number; district: number };
  verdict: Verdict;
  deltaPct: number;
  confidence: number;
  factors: ValuationFactor[];
  comparables: Comparable[];
  inspection: InspectionFinding[];
  financial: {
    acquisitionCosts: { label: string; amount: number }[];
    totalAcquisition: number;
    deposit: number;
    loanAmount: number;
    monthlyMortgage: number;
    annualRate: number;
    years: number;
    monthlyOwnership: number;
  };
  rental: { monthlyRent: number; grossYieldPct: number; netYieldPct: number };
  renovation: RenovationScenario[];
  negotiation: { openingOffer: number; targetPrice: number; maxOffer: number; arguments: string[] };
  scores: ScoreBreakdown;
};
