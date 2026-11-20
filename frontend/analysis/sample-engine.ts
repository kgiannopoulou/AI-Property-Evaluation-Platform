/**
 * Phase 3 SAMPLE analysis engine.
 *
 * A deterministic, rule-based stand-in that turns a property form into a
 * complete AnalysisResult so every screen can be designed and tested end to
 * end. The same input always yields the same output. It uses illustrative
 * reference values (see reference-data.ts), not market data, and performs no
 * image analysis — uploaded photos only affect the confidence score.
 *
 * Later phases replace this with:
 *   - valuation, financial, renovation  -> Java backend (Spring Boot)
 *   - inspection from photographs       -> AI service (Node + vision model)
 */
import type { Property } from "@/properties/types";
import {
  ACQUISITION_COSTS,
  MORTGAGE_DEFAULTS,
  RENOVATION_PER_SQM,
  districtReference,
} from "./reference-data";
import type {
  AnalysisResult,
  Comparable,
  InspectionFinding,
  RenovationScenario,
  ValuationFactor,
  Verdict,
} from "./types";

const clamp = (n: number, min: number, max: number) => Math.min(max, Math.max(min, n));
const roundTo = (n: number, step: number) => Math.round(n / step) * step;

/** Small deterministic PRNG (mulberry32) seeded from the property id. */
function seededRandom(seed: string) {
  let h = 1779033703 ^ seed.length;
  for (let i = 0; i < seed.length; i++) {
    h = Math.imul(h ^ seed.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  let a = h >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Standard annuity payment. */
export function monthlyPayment(principal: number, annualRate: number, years: number): number {
  const r = annualRate / 12;
  const n = years * 12;
  if (r === 0) return principal / n;
  return (principal * r) / (1 - Math.pow(1 + r, -n));
}

const ENERGY_IMPACT: Record<Property["energyClass"], number> = {
  "A+": 6, A: 5, "B+": 3, B: 2, C: 0, D: -2, E: -4, F: -6, G: -8,
};
const ENERGY_SCORE: Record<Property["energyClass"], number> = {
  "A+": 98, A: 92, "B+": 84, B: 76, C: 64, D: 52, E: 40, F: 28, G: 16,
};
const CONDITION_IMPACT: Record<Property["condition"], number> = {
  "needs-renovation": -15, fair: -6, good: 0, excellent: 7,
};
const CONDITION_SCORE: Record<Property["condition"], number> = {
  "needs-renovation": 30, fair: 55, good: 74, excellent: 92,
};
const TYPE_IMPACT: Record<Property["propertyType"], number> = {
  apartment: 0, maisonette: 4, "detached-house": 5, studio: 6, penthouse: 12,
};

function valuationFactors(p: Property, currentYear: number): ValuationFactor[] {
  const factors: ValuationFactor[] = [];
  const add = (label: string, impactPct: number, detail: string) => {
    if (impactPct !== 0) factors.push({ label, impactPct: Math.round(impactPct * 10) / 10, detail });
  };

  add("Property type", TYPE_IMPACT[p.propertyType], `${p.propertyType.replace("-", " ")} premium versus a standard apartment`);

  const effectiveYear = p.renovatedYear ?? p.yearBuilt;
  const age = Math.max(0, currentYear - effectiveYear);
  const ageImpact = age <= 5 ? 6 : clamp(-(age - 10) * 0.45, -18, 3);
  add(
    "Building age",
    ageImpact,
    p.renovatedYear ? `Renovated in ${p.renovatedYear} (built ${p.yearBuilt})` : `Built in ${p.yearBuilt}, ${age} years old`,
  );

  add("Condition", CONDITION_IMPACT[p.condition], `Declared condition: ${p.condition.replace("-", " ")}`);

  let floorImpact = 0;
  let floorDetail = "";
  if (p.floor < 0) [floorImpact, floorDetail] = [-15, "Basement level — light and humidity discount"];
  else if (p.floor === 0) [floorImpact, floorDetail] = [-6, "Ground floor — privacy and security discount"];
  else if (p.floor === 1) [floorImpact, floorDetail] = [-2, "First floor"];
  else if (p.floor >= 4 && p.elevator) [floorImpact, floorDetail] = [4, `Floor ${p.floor} with elevator — views and light`];
  else if (p.floor >= 3 && !p.elevator) [floorImpact, floorDetail] = [-5, `Floor ${p.floor} without an elevator`];
  add("Floor", floorImpact, floorDetail);

  add("Energy class", ENERGY_IMPACT[p.energyClass], `Energy performance certificate class ${p.energyClass}`);
  if (p.parking) add("Parking", 4, "Dedicated parking space");
  if (p.storage) add("Storage", 1.5, "Separate storage room");
  if (p.balconySqm > 0) add("Outdoor space", Math.min(p.balconySqm * 0.25, 4), `${p.balconySqm} m² of balcony / veranda`);
  if (p.sizeSqm > 150) add("Size", -4, "Large units trade at a lower price per m²");
  else if (p.sizeSqm < 40) add("Size", 5, "Small units trade at a higher price per m²");

  return factors;
}

function comparables(p: Property, estimatedPerSqm: number, currentYear: number): Comparable[] {
  const rand = seededRandom(p.id);
  const labels = ["Apartment", "Apartment", "Renovated flat", "Corner apartment", "Apartment", "Top-floor flat"];
  return labels.map((label, i) => {
    const sizeSqm = Math.round(p.sizeSqm * (0.78 + rand() * 0.44));
    const pricePerSqm = roundTo(estimatedPerSqm * (0.86 + rand() * 0.28), 10);
    return {
      id: `${p.id}-c${i + 1}`,
      label: `${label} · ${sizeSqm} m²`,
      district: p.district,
      sizeSqm,
      pricePerSqm,
      price: roundTo(sizeSqm * pricePerSqm, 1000),
      yearBuilt: clamp(Math.round(p.yearBuilt + (rand() - 0.5) * 30), 1950, currentYear),
      distanceKm: Math.round((0.2 + rand() * 1.6) * 10) / 10,
    };
  });
}

function inspection(p: Property, currentYear: number): InspectionFinding[] {
  const findings: InspectionFinding[] = [];
  const age = currentYear - p.yearBuilt;
  const renovated = p.renovatedYear !== null && currentYear - p.renovatedYear < 15;

  if (age > 35 && !renovated)
    findings.push({
      area: "Electrical",
      finding: "Wiring likely predates current standards — request an electrical installation certificate.",
      severity: "medium",
      estimatedCost: 3500,
    });
  if (age > 25 && !renovated)
    findings.push({
      area: "Plumbing",
      finding: "Original pipework is common at this age — check for galvanised pipes and water pressure.",
      severity: "medium",
      estimatedCost: 4000,
    });
  if (p.condition === "needs-renovation")
    findings.push({
      area: "Finishes",
      finding: "Declared as needing renovation — budget for floors, paint, doors and wet areas.",
      severity: "high",
      estimatedCost: roundTo(p.sizeSqm * 120, 500),
    });
  if (["E", "F", "G"].includes(p.energyClass))
    findings.push({
      area: "Windows & insulation",
      finding: `Energy class ${p.energyClass} suggests single glazing or no insulation — expect high running costs.`,
      severity: "medium",
      estimatedCost: roundTo(p.sizeSqm * 45, 500),
    });
  if (p.heating === "none")
    findings.push({
      area: "Heating",
      finding: "No heating system recorded — a heat pump or autonomous system will be required.",
      severity: "high",
      estimatedCost: 6000,
    });
  else if (p.heating === "central-oil")
    findings.push({
      area: "Heating",
      finding: "Central oil heating — higher running costs and shared-building dependency.",
      severity: "low",
      estimatedCost: 0,
    });
  if (p.floor <= 0)
    findings.push({
      area: "Moisture",
      finding: "Ground or basement level — check walls for rising damp and ventilation.",
      severity: "low",
      estimatedCost: 1500,
    });
  if (p.floor >= 3 && !p.elevator)
    findings.push({
      area: "Accessibility",
      finding: "No elevator above the second floor limits resale and rental demand.",
      severity: "low",
      estimatedCost: 0,
    });

  findings.push(
    p.photos.length === 0
      ? {
          area: "Photographs",
          finding: "No photographs uploaded — add photos so the AI Home Inspector can run when it ships.",
          severity: "info",
          estimatedCost: 0,
        }
      : {
          area: "Photographs",
          finding: `${p.photos.length} photograph${p.photos.length === 1 ? "" : "s"} received. Visual defect detection is not active yet; findings above are rule-based.`,
          severity: "info",
          estimatedCost: 0,
        },
  );
  return findings;
}

function renovationScenarios(p: Property, mid: number): RenovationScenario[] {
  const conditionMultiplier = { "needs-renovation": 1.2, fair: 1, good: 0.85, excellent: 0.6 }[p.condition];
  const upliftOffset = { "needs-renovation": 0.04, fair: 0.02, good: 0, excellent: -0.04 }[p.condition];
  const make = (
    tier: RenovationScenario["tier"],
    label: string,
    uplift: number,
    scope: string[],
  ): RenovationScenario => ({
    tier,
    label,
    cost: roundTo(RENOVATION_PER_SQM[tier] * p.sizeSqm * conditionMultiplier, 500),
    valueAfter: roundTo(mid * (1 + Math.max(0.01, uplift + upliftOffset)), 1000),
    scope,
  });
  return [
    make("basic", "Basic refresh", 0.03, ["Paint throughout", "Minor repairs", "New light fittings", "Deep clean"]),
    make("medium", "Medium renovation", 0.08, [
      "Everything in Basic",
      "New kitchen",
      "Bathroom refurbishment",
      "Flooring",
      "Interior doors",
    ]),
    make("premium", "Premium renovation", 0.13, [
      "Everything in Medium",
      "Electrical & plumbing renewal",
      "Double-glazed windows",
      "Heat pump & insulation",
      "Layout changes",
    ]),
  ];
}

export type MortgageAssumptions = { loanToValue: number; annualRate: number; years: number };

export function analyzeProperty(
  p: Property,
  now: Date = new Date(),
  mortgage: MortgageAssumptions = MORTGAGE_DEFAULTS,
): AnalysisResult {
  const currentYear = now.getFullYear();
  const district = districtReference(p.district);
  const factors = valuationFactors(p, currentYear);

  const multiplier = factors.reduce((m, f) => m * (1 + f.impactPct / 100), 1);
  const estimatedPerSqm = roundTo(district.salePerSqm * multiplier, 10);
  const mid = roundTo(estimatedPerSqm * p.sizeSqm, 1000);

  const confidence = clamp(
    74 + Math.min(p.photos.length, 6) * 2 - (p.district.startsWith("Other") ? 12 : 0) - (p.notes ? 0 : 2),
    40,
    90,
  );
  const spread = 0.06 + (90 - confidence) / 400;
  const estimate = { low: roundTo(mid * (1 - spread), 1000), mid, high: roundTo(mid * (1 + spread), 1000) };

  const deltaPct = Math.round(((p.askingPrice - mid) / mid) * 1000) / 10;
  const verdict: Verdict = deltaPct < -5 ? "below-market" : deltaPct > 5 ? "above-market" : "fair";

  // Financial snapshot, based on the asking price.
  const c = ACQUISITION_COSTS;
  const acquisitionCosts = [
    { label: "Transfer tax (3.09%)", amount: Math.round(p.askingPrice * c.transferTaxRate) },
    { label: "Notary fees (~1%)", amount: Math.round(p.askingPrice * c.notaryRate) },
    { label: "Lawyer (~1%)", amount: Math.round(p.askingPrice * c.legalRate) },
    { label: "Land registry (~0.5%)", amount: Math.round(p.askingPrice * c.landRegistryRate) },
    { label: "Agent commission (2%)", amount: Math.round(p.askingPrice * c.agentRate) },
  ];
  const costsTotal = acquisitionCosts.reduce((s, x) => s + x.amount, 0);
  const { loanToValue, annualRate, years } = mortgage;
  const loanAmount = Math.round(p.askingPrice * loanToValue);
  const deposit = p.askingPrice - loanAmount;
  const monthlyMortgage = Math.round(monthlyPayment(loanAmount, annualRate, years));
  const commonCharges = p.propertyType === "detached-house" ? 0 : p.sizeSqm * 1.1;
  const propertyTax = (p.sizeSqm * 4) / 12;
  const insurance = (p.sizeSqm * 1.5) / 12;
  const monthlyOwnership = Math.round(monthlyMortgage + commonCharges + propertyTax + insurance);

  // Rental view.
  const rentFactor = { "needs-renovation": 0.85, fair: 0.95, good: 1, excellent: 1.1 }[p.condition];
  const monthlyRent = roundTo(district.rentPerSqm * p.sizeSqm * rentFactor, 10);
  const grossYieldPct = Math.round(((monthlyRent * 12) / p.askingPrice) * 1000) / 10;
  const netYieldPct = Math.round(((monthlyRent * 12 * 0.72) / (p.askingPrice + costsTotal)) * 1000) / 10;

  const inspectionFindings = inspection(p, currentYear);
  const comps = comparables(p, estimatedPerSqm, currentYear);
  const compMedian = [...comps].map((x) => x.pricePerSqm).sort((a, b) => a - b)[Math.floor(comps.length / 2)];

  // Negotiation guidance.
  const repairs = inspectionFindings.reduce((s, f) => s + f.estimatedCost, 0);
  const targetPrice = roundTo(Math.min(p.askingPrice, mid), 1000);
  const openingOffer = roundTo(
    verdict === "below-market" ? p.askingPrice * 0.95 : Math.min(targetPrice * 0.92, p.askingPrice * 0.9),
    1000,
  );
  const maxOffer = roundTo(Math.min(p.askingPrice, mid * 1.03), 1000);
  const args: string[] = [];
  if (deltaPct > 0)
    args.push(`The asking price is ${deltaPct}% above the estimated value of €${mid.toLocaleString("en-GB")}.`);
  const askingPerSqm = Math.round(p.askingPrice / p.sizeSqm);
  if (askingPerSqm > compMedian)
    args.push(`Comparable properties nearby trade around €${compMedian.toLocaleString("en-GB")}/m² versus €${askingPerSqm.toLocaleString("en-GB")}/m² asked.`);
  if (repairs > 0) args.push(`Identified work items total roughly €${repairs.toLocaleString("en-GB")}.`);
  if (ENERGY_IMPACT[p.energyClass] < 0) args.push(`Energy class ${p.energyClass} means higher running costs and upgrade spending.`);
  if (p.floor >= 3 && !p.elevator) args.push("No elevator narrows the pool of future buyers and tenants.");
  if (args.length === 0) args.push("The price is in line with the estimate — negotiate on terms (completion date, included furniture) rather than price.");

  const priceScore = Math.round(clamp(70 - deltaPct * 2.5, 5, 98));
  const conditionScore = Math.round(
    clamp(CONDITION_SCORE[p.condition] - Math.max(0, currentYear - (p.renovatedYear ?? p.yearBuilt) - 30) * 0.4, 5, 98),
  );
  const scores = {
    price: priceScore,
    location: district.locationScore,
    condition: conditionScore,
    energy: ENERGY_SCORE[p.energyClass],
    overall: 0,
  };
  scores.overall = Math.round(scores.price * 0.35 + scores.location * 0.25 + scores.condition * 0.25 + scores.energy * 0.15);

  return {
    propertyId: p.id,
    generatedAt: now.toISOString(),
    engine: "sample-v1",
    estimate,
    pricePerSqm: { asking: askingPerSqm, estimated: estimatedPerSqm, district: district.salePerSqm },
    verdict,
    deltaPct,
    confidence,
    factors,
    comparables: comps,
    inspection: inspectionFindings,
    financial: {
      acquisitionCosts,
      totalAcquisition: p.askingPrice + costsTotal,
      deposit,
      loanAmount,
      monthlyMortgage,
      annualRate,
      years,
      monthlyOwnership,
    },
    rental: { monthlyRent, grossYieldPct, netYieldPct },
    renovation: renovationScenarios(p, mid),
    negotiation: { openingOffer, targetPrice, maxOffer, arguments: args },
    scores,
  };
}
