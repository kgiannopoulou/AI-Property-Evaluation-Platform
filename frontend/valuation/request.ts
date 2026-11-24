/**
 * Builds the valuation engine request from a saved property and the evidence the browser has:
 * AI Home Inspector findings, rule-based checks and the user's repair quotes. Pure, unit-tested.
 */
import type { InspectionFinding } from "../analysis/types.ts";
import type { Property } from "../properties/types.ts";
import type { RepairQuote, ValuationRequest } from "./types.ts";

/** The subset of an AI finding the request needs (see inspection/api.ts). */
export type AiFindingLike = {
  categoryLabel: string;
  title: string;
  severity: "info" | "minor" | "moderate" | "major" | "critical";
  assessment: "observed" | "potential-concern";
};

const MAX_CONCERNS = 60;

export function buildValuationRequest(
  p: Property,
  evidence: { aiFindings?: AiFindingLike[]; ruleFindings?: InspectionFinding[]; repairQuotes?: RepairQuote[] } = {},
): ValuationRequest {
  const concerns: ValuationRequest["concerns"] = [];
  for (const f of evidence.aiFindings ?? []) {
    if (f.severity === "info") continue;
    concerns.push({
      area: f.categoryLabel.slice(0, 60),
      title: f.title.slice(0, 300),
      severity: f.severity,
      observed: f.assessment === "observed",
      source: "ai-inspection",
    });
  }
  // Rule-based checks are inferences from the declared details, so they count as potential concerns.
  for (const f of evidence.ruleFindings ?? []) {
    if (f.severity === "info") continue;
    concerns.push({ area: f.area.slice(0, 60), title: f.finding.slice(0, 300), severity: f.severity, observed: false, source: "rule-check" });
  }

  const repairEstimates = (evidence.repairQuotes ?? [])
    .filter((q) => q.item.trim() && q.low > 0 && q.high > 0)
    .slice(0, 30)
    .map((q) => ({
      item: q.item.trim().slice(0, 200),
      low: Math.min(q.low, q.high),
      high: Math.max(q.low, q.high),
      source: q.source.trim().slice(0, 200) || "Not stated",
      verified: q.verified,
    }));

  return {
    subject: {
      listingType: p.listingType,
      propertyType: p.propertyType,
      sizeSqm: p.sizeSqm,
      bedrooms: p.bedrooms,
      bathrooms: p.bathrooms,
      floor: p.floor,
      elevator: p.elevator,
      parking: p.parking,
      yearBuilt: p.yearBuilt,
      renovatedYear: p.renovatedYear,
      condition: p.condition,
      energyClass: p.energyClass,
      askingPrice: p.askingPrice,
      location: p.location ? { lat: p.location.lat, lon: p.location.lon } : null,
      // "Other / not listed" is a form option, not a district the database knows.
      district: p.district && !p.district.startsWith("Other") ? p.district : null,
    },
    concerns: concerns.slice(0, MAX_CONCERNS),
    repairEstimates,
  };
}
