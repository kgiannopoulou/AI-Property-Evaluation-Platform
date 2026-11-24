/**
 * The freemium plans, mirrored from the backend's PlanCatalog (the backend enforces them; this list
 * only describes them). Prices are initial hypotheses being tested, not final prices.
 */
export type PlanId = "free" | "premium" | "professional" | "business";

export type PlanInfo = {
  id: PlanId;
  name: string;
  /** null = custom (contract) pricing. */
  monthlyPriceCents: number | null;
  /** null = set by contract. */
  analysesPerMonth: number | null;
  audience: string;
  features: string[];
  highlighted?: boolean;
};

export const PLANS: PlanInfo[] = [
  {
    id: "free",
    name: "Free",
    monthlyPriceCents: 0,
    analysesPerMonth: 2,
    audience: "Trying the platform on a first property.",
    features: ["2 analyses per month", "Valuation range, verdict and scores", "Basic AI inspection (up to 6 photos)", "Cost calculator", "Compare 2 properties"],
  },
  {
    id: "premium",
    name: "Premium",
    monthlyPriceCents: 999,
    analysesPerMonth: 20,
    audience: "Active buyers comparing several homes.",
    features: [
      "20 analyses per month",
      "Advanced valuation with comparable evidence",
      "PDF reports",
      "Full AI inspection (up to 24 photos)",
      "Location, market, building, documents and floor plans",
      "Negotiation assistant · compare 4 properties",
    ],
    highlighted: true,
  },
  {
    id: "professional",
    name: "Professional",
    monthlyPriceCents: 2999,
    analysesPerMonth: 100,
    audience: "Investors and agents evaluating for clients.",
    features: ["100 analyses per month", "Everything in Premium", "Investment tools: yield, ROI, cash flow", "Client-branded reports", "Compare 6 properties"],
  },
  {
    id: "business",
    name: "Business",
    monthlyPriceCents: null,
    analysesPerMonth: null,
    audience: "Agencies, valuers and lenders.",
    features: ["Analyses set by contract", "Teams with a shared allowance", "Bulk analysis", "API access", "Everything in Professional"],
  },
];

export function planInfo(id: PlanId): PlanInfo {
  return PLANS.find((p) => p.id === id) ?? PLANS[0];
}

/** "€9.99", "€0", or "Custom". */
export function formatPlanPrice(p: PlanInfo): string {
  if (p.monthlyPriceCents === null) return "Custom";
  const euros = p.monthlyPriceCents / 100;
  return `€${Number.isInteger(euros) ? euros : euros.toFixed(2)}`;
}
