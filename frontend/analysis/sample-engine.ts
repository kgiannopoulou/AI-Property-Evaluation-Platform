/**
 * SAMPLE analysis engine.
 *
 * A deterministic, rule-based stand-in that turns a property submission into
 * a complete AnalysisResult so every screen can be designed and tested end to
 * end. The same input always yields the same output. It uses illustrative
 * reference values (see reference-data.ts), not market data, and performs no
 * image analysis — photos only affect the confidence score.
 *
 * Purchases are judged on the asking price; rentals on the asking monthly rent.
 *
 * Later phases replace this with:
 *   - valuation, financial, renovation  -> Java backend (Spring Boot)
 *   - inspection from photographs       -> AI service (Node + vision model)
 */
import type { Property } from "../properties/types.ts";
import {
  ACQUISITION_COSTS,
  APPLIANCE_REPLACEMENT_COST,
  MORTGAGE_DEFAULTS,
  RENOVATION_PER_SQM,
  TENANCY_COSTS,
  districtReference,
} from "./reference-data.ts";
import type {
  AnalysisResult,
  Comparable,
  InspectionFinding,
  RenovationScenario,
  ValuationFactor,
  Verdict,
} from "./types.ts";

const clamp = (n: number, min: number, max: number) => Math.min(max, Math.max(min, n));
const roundTo = (n: number, step: number) => Math.round(n / step) * step;
const eur = (n: number) => `€${Math.round(n).toLocaleString("en-GB")}`;

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
const FACILITY_IMPACT: Partial<Record<Property["facilities"][number], number>> = {
  pool: 3, garden: 2.5, "roof-terrace": 2.5, fireplace: 0.5, "double-glazing": 1, "solar-water-heater": 0.5,
  "security-door": 0.3, alarm: 0.3,
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

  add(
    "Energy class",
    ENERGY_IMPACT[p.energyClass],
    p.energyCertificate.status === "issued"
      ? `Energy performance certificate class ${p.energyClass}`
      : `Estimated class ${p.energyClass} (no certificate yet)`,
  );
  if (p.parking) add("Parking", 4, "Dedicated parking space");
  if (p.storage) add("Storage", 1.5, "Separate storage room");
  if (p.balconySqm > 0) add("Outdoor space", Math.min(p.balconySqm * 0.25, 4), `${p.balconySqm} m² of balcony / veranda`);
  if (p.sizeSqm > 150) add("Size", -4, "Large units trade at a lower price per m²");
  else if (p.sizeSqm < 40) add("Size", 5, "Small units trade at a higher price per m²");

  const facilityImpact = Math.min(
    p.facilities.reduce((s, f) => s + (FACILITY_IMPACT[f] ?? 0), 0),
    5,
  );
  const notable = p.facilities.filter((f) => (FACILITY_IMPACT[f] ?? 0) >= 1).map((f) => f.replace("-", " "));
  add("Facilities", facilityImpact, notable.length ? `Includes ${notable.join(", ")}` : "Smaller extras included");

  if (p.cooling === "central-ac" || p.cooling === "heat-pump")
    add("Cooling", 1.5, p.cooling === "heat-pump" ? "Heat pump for heating and cooling" : "Central air-conditioning");

  if (p.listingType === "rent") {
    const furnished = { included: 8, partial: 3, "not-included": 0 }[p.checklist.furniture];
    add("Furniture", furnished, p.checklist.furniture === "included" ? "Let fully furnished" : "Let partly furnished");
  }

  return factors;
}

function comparables(p: Property, estimatedPerSqm: number, currentYear: number, step: number): Comparable[] {
  const rand = seededRandom(p.id);
  const labels = ["Apartment", "Apartment", "Renovated flat", "Corner apartment", "Apartment", "Top-floor flat"];
  const perSqmStep = step === 1000 ? 10 : 0.5;
  return labels.map((label, i) => {
    const sizeSqm = Math.round(p.sizeSqm * (0.78 + rand() * 0.44));
    const pricePerSqm = roundTo(estimatedPerSqm * (0.86 + rand() * 0.28), perSqmStep);
    return {
      id: `${p.id}-c${i + 1}`,
      label: `${label} · ${sizeSqm} m²`,
      district: p.district,
      sizeSqm,
      pricePerSqm,
      price: roundTo(sizeSqm * pricePerSqm, step),
      yearBuilt: clamp(Math.round(p.yearBuilt + (rand() - 0.5) * 30), 1950, currentYear),
      distanceKm: Math.round((0.2 + rand() * 1.6) * 10) / 10,
    };
  });
}

function inspection(p: Property, currentYear: number): InspectionFinding[] {
  const findings: InspectionFinding[] = [];
  const age = currentYear - p.yearBuilt;
  const renovated = p.renovatedYear !== null && currentYear - p.renovatedYear < 15;
  const tenant = p.listingType === "rent";

  if (!tenant && age > 35 && !renovated)
    findings.push({
      area: "Electrical",
      finding: "Wiring likely predates current standards — request an electrical installation certificate.",
      severity: "medium",
      estimatedCost: 3500,
    });
  if (!tenant && age > 25 && !renovated)
    findings.push({
      area: "Plumbing",
      finding: "Original pipework is common at this age — check for galvanised pipes and water pressure.",
      severity: "medium",
      estimatedCost: 4000,
    });
  if (p.constructionType === "masonry" && p.yearBuilt < 1960)
    findings.push({
      area: "Structure",
      finding: "Pre-1960 masonry building — ask for a structural engineer's opinion on seismic resilience.",
      severity: tenant ? "low" : "medium",
      estimatedCost: tenant ? 0 : 600,
    });
  if (p.condition === "needs-renovation")
    findings.push({
      area: "Finishes",
      finding: tenant
        ? "Declared as needing renovation — agree in writing which repairs the landlord makes before you move in."
        : "Declared as needing renovation — budget for floors, paint, doors and wet areas.",
      severity: "high",
      estimatedCost: tenant ? 0 : roundTo(p.sizeSqm * 120, 500),
    });
  if (["E", "F", "G"].includes(p.energyClass))
    findings.push({
      area: "Windows & insulation",
      finding: `Energy class ${p.energyClass} suggests single glazing or no insulation — expect high running costs.`,
      severity: "medium",
      estimatedCost: tenant ? 0 : roundTo(p.sizeSqm * 45, 500),
    });
  if (p.energyCertificate.status !== "issued")
    findings.push({
      area: "Energy certificate",
      finding:
        p.energyCertificate.status === "pending"
          ? "Energy certificate applied for — it is required before a sale or lease can be signed."
          : "No energy certificate on record — it is legally required to sell or let; the class shown is an estimate.",
      severity: "low",
      estimatedCost: tenant ? 0 : 150,
    });

  // Heating: the installed system and whether it actually works.
  if (p.heating === "none" || p.checklist.heating === "unavailable")
    findings.push({
      area: "Heating",
      finding:
        p.heating === "none"
          ? "No heating system recorded — a heat pump or autonomous system will be required."
          : "Heating is reported as unavailable — find out why before committing.",
      severity: "high",
      estimatedCost: tenant ? 0 : 6000,
    });
  else if (p.heating === "central-oil")
    findings.push({
      area: "Heating",
      finding: p.buildingExpenses.includesHeating
        ? "Central oil heating, paid through the common charges — costs depend on the whole building's usage."
        : "Central oil heating — higher running costs and shared-building dependency.",
      severity: "low",
      estimatedCost: 0,
    });
  if (p.checklist.heating === "unknown" && p.heating !== "none")
    findings.push({
      area: "Heating",
      finding: "Heating has not been confirmed as working — test it during the viewing.",
      severity: "info",
      estimatedCost: 0,
    });
  if (p.cooling === "none")
    findings.push({
      area: "Cooling",
      finding: "No cooling — summer comfort and rentability are lower; split units cost about €850 each installed.",
      severity: "low",
      estimatedCost: tenant ? 0 : 850 * Math.max(1, p.bedrooms),
    });

  // Appliance checklist.
  const damaged = Object.entries(p.checklist.appliances).filter(([, s]) => s === "damaged");
  const missing = Object.entries(p.checklist.appliances).filter(([, s]) => s === "missing");
  const unknown = Object.entries(p.checklist.appliances).filter(([, s]) => s === "unknown");
  const name = (k: string) => k.replace("-", " ");
  const cost = (k: string) => APPLIANCE_REPLACEMENT_COST[k as keyof typeof APPLIANCE_REPLACEMENT_COST];
  if (damaged.length)
    findings.push({
      area: "Appliances",
      finding: tenant
        ? `Damaged: ${damaged.map(([k]) => name(k)).join(", ")}. Ask the landlord to repair or replace them before the lease starts.`
        : `Damaged: ${damaged.map(([k]) => name(k)).join(", ")}. Budget for repair or replacement.`,
      severity: "medium",
      estimatedCost: damaged.reduce((s, [k]) => s + cost(k), 0),
    });
  if (missing.length)
    findings.push({
      area: "Appliances",
      finding: `Not included: ${missing.map(([k]) => name(k)).join(", ")}.`,
      severity: "low",
      estimatedCost: missing.reduce((s, [k]) => s + cost(k), 0),
    });
  if (unknown.length)
    findings.push({
      area: "Appliances",
      finding: `Status not confirmed: ${unknown.map(([k]) => name(k)).join(", ")}. Check them and get the inclusions in writing.`,
      severity: "info",
      estimatedCost: 0,
    });

  // Building.
  const charges = p.buildingExpenses.monthlyCommonCharges;
  if (charges !== null && p.propertyType !== "detached-house" && charges / p.sizeSqm > 2.2)
    findings.push({
      area: "Building costs",
      finding: `Common charges of ${eur(charges)}/month (${(charges / p.sizeSqm).toFixed(2)} €/m²) are high — ask for the last year's statements.`,
      severity: "low",
      estimatedCost: 0,
    });
  if (p.buildingExpenses.plannedWorks)
    findings.push({
      area: "Building works",
      finding: `Planned building works: “${p.buildingExpenses.plannedWorks}”. Confirm who pays and how much before signing.`,
      severity: "medium",
      estimatedCost: 0,
    });

  if (p.floor <= 0)
    findings.push({
      area: "Moisture",
      finding: "Ground or basement level — check walls for rising damp and ventilation.",
      severity: "low",
      estimatedCost: tenant ? 0 : 1500,
    });
  if (p.floor >= 3 && !p.elevator)
    findings.push({
      area: "Accessibility",
      finding: tenant
        ? "No elevator above the second floor — consider deliveries, moving and visitors."
        : "No elevator above the second floor limits resale and rental demand.",
      severity: "low",
      estimatedCost: 0,
    });

  const areas = new Set(p.photos.map((x) => x.area));
  findings.push(
    p.photos.length === 0
      ? {
          area: "Photographs",
          finding: "No photographs uploaded — add photos of each room so the AI Home Inspector can run when it ships.",
          severity: "info",
          estimatedCost: 0,
        }
      : {
          area: "Photographs",
          finding: `${p.photos.length} photograph${p.photos.length === 1 ? "" : "s"} across ${areas.size} area${areas.size === 1 ? "" : "s"} received. Visual defect detection is not active yet; findings above are rule-based.`,
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
  const rent = p.listingType === "rent";
  const district = districtReference(p.district);
  const factors = valuationFactors(p, currentYear);
  const multiplier = factors.reduce((m, f) => m * (1 + f.impactPct / 100), 1);
  // Furniture changes what a tenant pays, not what the property is worth.
  const saleMultiplier = factors
    .filter((f) => f.label !== "Furniture")
    .reduce((m, f) => m * (1 + f.impactPct / 100), 1);

  // Sale value (always) and market rent (always); the "basis" is what the asking figure is compared with.
  const salePerSqm = roundTo(district.salePerSqm * saleMultiplier, 10);
  const saleMid = roundTo(salePerSqm * p.sizeSqm, 1000);
  const rentFactor = { "needs-renovation": 0.85, fair: 0.95, good: 1, excellent: 1.1 }[p.condition];
  const rentPerSqm = Math.round(district.rentPerSqm * (rent ? multiplier : rentFactor) * 10) / 10;
  const monthlyRent = roundTo(rentPerSqm * p.sizeSqm, 10);

  const step = rent ? 10 : 1000;
  const estimatedPerSqm = rent ? rentPerSqm : salePerSqm;
  const mid = rent ? monthlyRent : saleMid;

  const photoAreas = new Set(p.photos.map((x) => x.area)).size;
  const certainChecklist = Object.values(p.checklist.appliances).filter((s) => s !== "unknown").length;
  const confidence = clamp(
    70 +
      Math.min(photoAreas, 6) * 2 +
      (p.location ? 2 : 0) +
      (p.energyCertificate.status === "issued" ? 2 : 0) +
      (certainChecklist >= 4 ? 2 : 0) -
      (p.district.startsWith("Other") ? 12 : 0) -
      (p.notes ? 0 : 2),
    40,
    90,
  );
  const spread = 0.06 + (90 - confidence) / 400;
  const estimate = { low: roundTo(mid * (1 - spread), step), mid, high: roundTo(mid * (1 + spread), step) };

  const deltaPct = Math.round(((p.askingPrice - mid) / mid) * 1000) / 10;
  const verdict: Verdict = deltaPct < -5 ? "below-market" : deltaPct > 5 ? "above-market" : "fair";

  // Building costs: the declared common charges, or an estimate when unknown.
  const commonCharges =
    p.buildingExpenses.monthlyCommonCharges ?? (p.propertyType === "detached-house" ? 0 : Math.round(p.sizeSqm * 1.1));

  // Buying: priced at the asking price for a sale, at the estimated value for a rental ("buy instead").
  const buyPrice = rent ? saleMid : p.askingPrice;
  const c = ACQUISITION_COSTS;
  const acquisitionCosts = [
    { label: "Transfer tax (3.09%)", amount: Math.round(buyPrice * c.transferTaxRate) },
    { label: "Notary fees (~1%)", amount: Math.round(buyPrice * c.notaryRate) },
    { label: "Lawyer (~1%)", amount: Math.round(buyPrice * c.legalRate) },
    { label: "Land registry (~0.5%)", amount: Math.round(buyPrice * c.landRegistryRate) },
    { label: "Agent commission (2%)", amount: Math.round(buyPrice * c.agentRate) },
  ];
  const costsTotal = acquisitionCosts.reduce((s, x) => s + x.amount, 0);
  const { loanToValue, annualRate, years } = mortgage;
  const loanAmount = Math.round(buyPrice * loanToValue);
  const deposit = buyPrice - loanAmount;
  const monthlyMortgage = Math.round(monthlyPayment(loanAmount, annualRate, years));
  const propertyTax = (p.sizeSqm * 4) / 12;
  const insurance = (p.sizeSqm * 1.5) / 12;
  const monthlyOwnership = Math.round(monthlyMortgage + commonCharges + propertyTax + insurance);

  // Letting it out (landlord view).
  const grossYieldPct = Math.round(((monthlyRent * 12) / buyPrice) * 1000) / 10;
  const netYieldPct = Math.round(((monthlyRent * 12 * 0.72) / (buyPrice + costsTotal)) * 1000) / 10;

  // Renting it (tenant view).
  let tenancy: AnalysisResult["tenancy"];
  if (rent) {
    const t = TENANCY_COSTS;
    const moveInCosts = [
      { label: "First month's rent", amount: p.askingPrice },
      { label: `Deposit (${t.depositMonths} months)`, amount: p.askingPrice * t.depositMonths },
      { label: "Agent fee (1 month + VAT)", amount: Math.round(p.askingPrice * t.agentFeeMonths * (1 + t.vatRate)) },
    ];
    const missingAppliances = Object.entries(p.checklist.appliances)
      .filter(([, s]) => s === "missing")
      .reduce((s, [k]) => s + APPLIANCE_REPLACEMENT_COST[k as keyof typeof APPLIANCE_REPLACEMENT_COST], 0);
    if (missingAppliances) moveInCosts.push({ label: "Appliances to buy", amount: missingAppliances });
    if (p.checklist.furniture === "not-included")
      moveInCosts.push({ label: "Furniture (basic)", amount: roundTo(1500 + p.bedrooms * 900, 100) });
    const monthlyBreakdown = [
      { label: "Rent", amount: p.askingPrice },
      { label: "Common charges", amount: commonCharges },
      { label: "Utilities (estimate)", amount: Math.round(p.sizeSqm * t.utilitiesPerSqm) },
    ];
    tenancy = {
      moveInCosts,
      totalMoveIn: moveInCosts.reduce((s, x) => s + x.amount, 0),
      monthlyCost: monthlyBreakdown.reduce((s, x) => s + x.amount, 0),
      monthlyBreakdown,
    };
  }

  const inspectionFindings = inspection(p, currentYear);
  const comps = comparables(p, estimatedPerSqm, currentYear, step);
  const compMedian = [...comps].map((x) => x.pricePerSqm).sort((a, b) => a - b)[Math.floor(comps.length / 2)];

  // Negotiation guidance (on the price, or on the monthly rent).
  const repairs = inspectionFindings.reduce((s, f) => s + f.estimatedCost, 0);
  const targetPrice = roundTo(Math.min(p.askingPrice, mid), step);
  const openingOffer = roundTo(
    verdict === "below-market" ? p.askingPrice * 0.95 : Math.min(targetPrice * 0.92, p.askingPrice * 0.9),
    step,
  );
  const maxOffer = roundTo(Math.min(p.askingPrice, mid * 1.03), step);
  const unit = rent ? "/month" : "";
  const perSqmUnit = rent ? "/m² per month" : "/m²";
  const askingPerSqm = rent ? Math.round((p.askingPrice / p.sizeSqm) * 10) / 10 : Math.round(p.askingPrice / p.sizeSqm);
  const args: string[] = [];
  if (deltaPct > 0)
    args.push(
      `The asking ${rent ? "rent" : "price"} is ${deltaPct}% above the estimated ${rent ? "market rent" : "value"} of ${eur(mid)}${unit}.`,
    );
  if (askingPerSqm > compMedian)
    args.push(`Comparable properties nearby ${rent ? "let" : "trade"} around €${compMedian.toLocaleString("en-GB")}${perSqmUnit} versus €${askingPerSqm.toLocaleString("en-GB")}${perSqmUnit} asked.`);
  if (!rent && repairs > 0) args.push(`Identified work items total roughly ${eur(repairs)}.`);
  const damaged = Object.values(p.checklist.appliances).filter((s) => s === "damaged").length;
  if (damaged) args.push(`${damaged} appliance${damaged === 1 ? " is" : "s are"} damaged — ask for repair, replacement or a reduction.`);
  if (ENERGY_IMPACT[p.energyClass] < 0) args.push(`Energy class ${p.energyClass} means higher running costs${rent ? "" : " and upgrade spending"}.`);
  if (p.buildingExpenses.plannedWorks) args.push("Planned building works are a cost risk — make them part of the agreement.");
  if (p.floor >= 3 && !p.elevator) args.push(`No elevator narrows the pool of future ${rent ? "tenants" : "buyers and tenants"}.`);
  if (args.length === 0)
    args.push(
      rent
        ? "The rent is in line with the estimate — negotiate on terms (lease length, included furniture, repairs before move-in) rather than price."
        : "The price is in line with the estimate — negotiate on terms (completion date, included furniture) rather than price.",
    );

  const priceScore = Math.round(clamp(70 - deltaPct * 2.5, 5, 98));
  const conditionScore = Math.round(
    clamp(
      CONDITION_SCORE[p.condition] -
        Math.max(0, currentYear - (p.renovatedYear ?? p.yearBuilt) - 30) * 0.4 -
        damaged * 3,
      5,
      98,
    ),
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
    mode: rent ? "rent" : "sale",
    propertyValue: saleMid,
    generatedAt: now.toISOString(),
    engine: "sample-v2",
    estimate,
    pricePerSqm: { asking: askingPerSqm, estimated: estimatedPerSqm, district: rent ? district.rentPerSqm : district.salePerSqm },
    verdict,
    deltaPct,
    confidence,
    factors,
    comparables: comps,
    inspection: inspectionFindings,
    financial: {
      acquisitionCosts,
      totalAcquisition: buyPrice + costsTotal,
      deposit,
      loanAmount,
      monthlyMortgage,
      annualRate,
      years,
      monthlyOwnership,
    },
    rental: { monthlyRent, grossYieldPct, netYieldPct },
    tenancy,
    renovation: renovationScenarios(p, saleMid),
    negotiation: { openingOffer, targetPrice, maxOffer, arguments: args },
    scores,
  };
}
