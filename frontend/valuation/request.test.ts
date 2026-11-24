import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { SAMPLE_PROPERTIES } from "../properties/sample-data.ts";
import type { Property } from "../properties/types.ts";
import { buildValuationRequest } from "./request.ts";

const base = SAMPLE_PROPERTIES.find((p) => p.id === "sample-marousi")!;
const with_ = (o: Partial<Property>): Property => ({ ...base, ...o });

describe("valuation request", () => {
  it("maps the declared details onto the engine's subject", () => {
    const r = buildValuationRequest(with_({ location: { lat: 38.05, lon: 23.8 } }));
    assert.equal(r.subject.sizeSqm, base.sizeSqm);
    assert.equal(r.subject.askingPrice, base.askingPrice);
    assert.deepEqual(r.subject.location, { lat: 38.05, lon: 23.8 });
    assert.equal(r.subject.district, base.district);
    assert.deepEqual(r.concerns, []);
  });

  it("does not send the 'Other / not listed' placeholder as a district", () => {
    assert.equal(buildValuationRequest(with_({ district: "Other / not listed" })).subject.district, null);
  });

  it("keeps AI observations, marks rule checks as potential and drops info items", () => {
    const r = buildValuationRequest(base, {
      aiFindings: [
        { categoryLabel: "Bathroom", title: "Bathroom requires renovation", severity: "major", assessment: "observed" },
        { categoryLabel: "Windows", title: "Windows may need replacement", severity: "moderate", assessment: "potential-concern" },
        { categoryLabel: "Photos", title: "Good light", severity: "info", assessment: "observed" },
      ],
      ruleFindings: [
        { area: "Plumbing", finding: "Original pipework is common at this age", severity: "medium", estimatedCost: 4000 },
        { area: "Photographs", finding: "3 photographs received", severity: "info", estimatedCost: 0 },
      ],
    });
    assert.deepEqual(
      r.concerns.map((c) => [c.title, c.observed, c.source]),
      [
        ["Bathroom requires renovation", true, "ai-inspection"],
        ["Windows may need replacement", false, "ai-inspection"],
        ["Original pipework is common at this age", false, "rule-check"],
      ],
    );
  });

  it("sends only complete repair quotes, with low ≤ high", () => {
    const r = buildValuationRequest(base, {
      repairQuotes: [
        { id: "1", item: "Kitchen", low: 9000, high: 7000, source: "Quote", verified: true },
        { id: "2", item: " ", low: 100, high: 200, source: "", verified: false },
        { id: "3", item: "Windows", low: 0, high: 5000, source: "", verified: false },
      ],
    });
    assert.deepEqual(r.repairEstimates, [{ item: "Kitchen", low: 7000, high: 9000, source: "Quote", verified: true }]);
  });
});
