/**
 * Builds the backend report request (POST /api/v1/reports/pdf) from a saved property. The backend
 * runs every module itself, so the PDF never contains figures from the browser's sample engine.
 * Pure, unit-tested.
 */
import type { InspectionFinding } from "../analysis/types.ts";
import type { Property } from "../properties/types.ts";
import { buildValuationRequest, type AiFindingLike } from "../valuation/request.ts";
import type { RepairQuote, ValuationRequest } from "../valuation/types.ts";

export type MortgagePrefs = { loanToValuePct: number; mortgageRatePct: number; mortgageYears: number };

export type ReportRequest = {
  title: string;
  address: string | null;
  valuation: ValuationRequest;
  financial: unknown | null;
  renovation: unknown | null;
  building: unknown;
  includeLocation: boolean;
};

export function buildReportRequest(
  p: Property,
  evidence: { aiFindings?: AiFindingLike[]; ruleFindings?: InspectionFinding[]; repairQuotes?: RepairQuote[] },
  mortgage: MortgagePrefs,
): ReportRequest {
  const valuation = buildValuationRequest(p, evidence);
  const sale = p.listingType === "sale";
  const charges = p.buildingExpenses.monthlyCommonCharges;
  return {
    title: p.title.slice(0, 120),
    address: p.address ? p.address.slice(0, 200) : null,
    valuation,
    financial: sale
      ? {
          price: p.askingPrice,
          taxBasis: "transfer-tax",
          mortgage: {
            loanToValue: Math.min(1, Math.max(0, mortgage.loanToValuePct / 100)),
            annualRatePct: mortgage.mortgageRatePct,
            years: mortgage.mortgageYears,
          },
          ownership: { sizeSqm: p.sizeSqm, energyClass: p.energyClass, yearBuilt: p.yearBuilt, monthlyCommonCharges: charges },
        }
      : null,
    renovation: sale
      ? {
          sizeSqm: p.sizeSqm,
          bathrooms: p.bathrooms,
          yearBuilt: p.yearBuilt,
          condition: p.condition,
          concerns: valuation.concerns.map((c) => ({ area: c.area, title: c.title })),
        }
      : null,
    building: {
      yearBuilt: p.yearBuilt,
      propertyFloor: p.floor,
      propertySizeSqm: p.sizeSqm,
      structureType: p.constructionType,
      elevatorCount: p.elevator ? 1 : 0,
      monthlyCommonCharges: charges,
      chargesIncludeHeating: p.buildingExpenses.includesHeating,
      plannedWorks: p.buildingExpenses.plannedWorks.trim() ? p.buildingExpenses.plannedWorks.slice(0, 500) : null,
      windows: p.facilities.includes("double-glazing") ? "double" : null,
    },
    includeLocation: p.location !== null,
  };
}
