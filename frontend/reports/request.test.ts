import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { SAMPLE_PROPERTIES } from "../properties/sample-data.ts";
import type { Property } from "../properties/types.ts";
import { buildReportRequest } from "./request.ts";

const base = SAMPLE_PROPERTIES.find((p) => p.id === "sample-marousi")!;
const with_ = (o: Partial<Property>): Property => ({ ...base, ...o });
const PREFS = { loanToValuePct: 80, mortgageRatePct: 3.9, mortgageYears: 25 };

describe("report request", () => {
  it("asks the backend for every module of a purchase", () => {
    const r = buildReportRequest(with_({ location: { lat: 38.05, lon: 23.8 } }), {}, PREFS);
    assert.equal(r.valuation.subject.askingPrice, base.askingPrice);
    assert.deepEqual((r.financial as { mortgage: unknown }).mortgage, { loanToValue: 0.8, annualRatePct: 3.9, years: 25 });
    assert.ok(r.renovation);
    assert.equal((r.building as { elevatorCount: number }).elevatorCount, base.elevator ? 1 : 0);
    assert.equal(r.includeLocation, true);
  });

  it("leaves purchase-only modules out of a rental and skips location without a point", () => {
    const r = buildReportRequest(with_({ listingType: "rent", askingPrice: 950, location: null }), {}, PREFS);
    assert.equal(r.financial, null);
    assert.equal(r.renovation, null);
    assert.equal(r.includeLocation, false);
  });

  it("passes inspection concerns to the renovation planner", () => {
    const r = buildReportRequest(base, {
      aiFindings: [{ categoryLabel: "Kitchen", title: "Older kitchen", severity: "moderate", assessment: "observed" }],
    }, PREFS);
    assert.deepEqual((r.renovation as { concerns: unknown[] }).concerns, [{ area: "Kitchen", title: "Older kitchen" }]);
  });
});
