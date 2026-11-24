/** Shapes of the backend valuation engine (backend/.../valuation), POST /api/v1/valuations. */

export type RepairQuote = {
  id: string;
  item: string;
  low: number;
  high: number;
  /** e.g. "Written quote from a contractor", "Survey report". */
  source: string;
  /** Only verified quotes move the suggested offers. */
  verified: boolean;
};

export type ValuationRequest = {
  subject: {
    listingType: "sale" | "rent";
    propertyType: string;
    sizeSqm: number;
    bedrooms: number;
    bathrooms: number;
    floor: number;
    elevator: boolean;
    parking: boolean;
    yearBuilt: number;
    renovatedYear: number | null;
    condition: string;
    energyClass: string;
    askingPrice: number;
    location: { lat: number; lon: number } | null;
    district: string | null;
  };
  concerns: { area: string; title: string; severity: string; observed: boolean; source: string }[];
  repairEstimates: { item: string; low: number; high: number; source: string; verified: boolean }[];
};

export type ScoreItem = { label: string; points: number; detail: string };
export type Score = { score: number | null; basis: string; items: ScoreItem[] };

export type ComparableResult = {
  id: string;
  source: string;
  listingType: "sold" | "sale-listing" | "rented" | "rent-listing";
  address: string | null;
  sizeSqm: number;
  price: number;
  pricePerSqm: number;
  observedOn: string;
  distanceM: number | null;
  verified: boolean;
  adjustments: { factor: string; pct: number; detail: string }[];
  adjustedPricePerSqm: number | null;
  grossAdjustmentPct: number | null;
  weight: number | null;
  excludedReason: string | null;
};

export type ValuationReport = {
  status: "valued" | "insufficient-data";
  engine: string;
  mode: "sale" | "rent";
  generatedAt: string;
  askingPrice: number;
  askingPerSqm: number;
  estimate: { low: number; mid: number; high: number; perSqm: number; perSqmLow: number; perSqmHigh: number } | null;
  verdict: "below-market" | "fair" | "above-market" | null;
  deltaPct: number | null;
  conditionScore: Score;
  locationScore: Score;
  dataQuality: {
    grade: "high" | "medium" | "low" | "insufficient";
    score: number;
    sufficient: boolean;
    reasons: string[];
    warnings: string[];
    nextSteps: string[];
    metrics: {
      marketDataConnected: boolean;
      candidates: number;
      used: number;
      excluded: number;
      transactions: number;
      askingPrices: number;
      userSupplied: number;
      effectiveSampleSize: number;
      searchRadiusM: number | null;
      medianDistanceM: number | null;
      medianAgeMonths: number | null;
      dispersionPct: number | null;
      attributeCoveragePct: number | null;
      averageGrossAdjustmentPct: number | null;
      monthlyTrendPct: number | null;
    };
  };
  comparables: ComparableResult[];
  concerns: { area: string | null; title: string; severity: string; observed: boolean; source: string }[];
  repairs: {
    verifiedLow: number;
    verifiedHigh: number;
    unverifiedLow: number;
    unverifiedHigh: number;
    reflectedInValue: number;
    deductedFromOffer: number;
  };
  negotiation: { openingOffer: number; targetPrice: number; maxOffer: number; strategy: string; evidence: string[] } | null;
  methodology: string[];
  disclaimer: string;
};
