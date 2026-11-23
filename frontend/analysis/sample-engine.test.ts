import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { SAMPLE_PROPERTIES } from "../properties/sample-data.ts";
import type { Property } from "../properties/types.ts";
import { analyzeProperty } from "./sample-engine.ts";

const NOW = new Date("2026-11-23T12:00:00Z");
const base = SAMPLE_PROPERTIES.find((p) => p.id === "sample-marousi")!;
const with_ = (o: Partial<Property>): Property => ({ ...base, ...o });
const findings = (p: Property) => analyzeProperty(p, NOW).inspection;

describe("sample engine — purchases", () => {
  it("is deterministic", () => assert.deepEqual(analyzeProperty(base, NOW), analyzeProperty(base, NOW)));

  it("analyses every sample record without NaN", () => {
    for (const p of SAMPLE_PROPERTIES) {
      const a = analyzeProperty(p, NOW);
      assert.equal(a.mode, p.listingType);
      assert.equal(a.tenancy !== undefined, p.listingType === "rent");
      assert.ok(a.estimate.low <= a.estimate.mid && a.estimate.mid <= a.estimate.high);
      assert.ok(Number.isFinite(a.financial.monthlyOwnership));
    }
  });

  it("prices damaged and missing appliances from the checklist", () => {
    const a = findings(
      with_({
        checklist: {
          ...base.checklist,
          appliances: { ...base.checklist.appliances, refrigerator: "damaged", dishwasher: "missing" },
        },
      }),
    );
    const damaged = a.find((f) => f.area === "Appliances" && f.finding.startsWith("Damaged"));
    const missing = a.find((f) => f.area === "Appliances" && f.finding.startsWith("Not included"));
    assert.equal(damaged?.estimatedCost, 650);
    assert.equal(missing?.estimatedCost, 480);
  });

  it("flags unavailable heating, no cooling and a missing certificate", () => {
    const a = findings(
      with_({
        cooling: "none",
        energyCertificate: { status: "none", number: "" },
        checklist: { ...base.checklist, heating: "unavailable" },
      }),
    );
    assert.ok(a.some((f) => f.area === "Heating" && f.severity === "high"));
    assert.ok(a.some((f) => f.area === "Cooling"));
    assert.ok(a.some((f) => f.area === "Energy certificate"));
  });

  it("uses declared common charges in the monthly ownership cost", () => {
    const low = analyzeProperty(with_({ buildingExpenses: { ...base.buildingExpenses, monthlyCommonCharges: 20 } }), NOW);
    const high = analyzeProperty(with_({ buildingExpenses: { ...base.buildingExpenses, monthlyCommonCharges: 220 } }), NOW);
    assert.equal(high.financial.monthlyOwnership - low.financial.monthlyOwnership, 200);
  });

  it("raises confidence with photos covering more areas", () => {
    const photo = (area: Property["photos"][number]["area"], i: number) => ({ id: String(i), name: "", area, dataUrl: "" });
    const oneArea = analyzeProperty(with_({ photos: [0, 1, 2].map((i) => photo("kitchen", i)) }), NOW);
    const threeAreas = analyzeProperty(
      with_({ photos: [photo("kitchen", 0), photo("bathroom", 1), photo("exterior", 2)] }),
      NOW,
    );
    assert.ok(threeAreas.confidence > oneArea.confidence);
  });
});

describe("sample engine — rentals", () => {
  const rental = with_({ id: "rent-test", listingType: "rent", askingPrice: 1300 });

  it("compares the asking rent with the estimated market rent", () => {
    const a = analyzeProperty(rental, NOW);
    assert.equal(a.mode, "rent");
    assert.ok(a.estimate.mid > 500 && a.estimate.mid < 5000, `rent estimate ${a.estimate.mid}`);
    assert.equal(a.deltaPct, Math.round(((1300 - a.estimate.mid) / a.estimate.mid) * 1000) / 10);
    assert.ok((a.propertyValue ?? 0) > 100_000, "sale value is still reported");
    assert.ok(a.negotiation.maxOffer <= 1300);
  });

  it("lists tenant move-in costs, including furniture when not included", () => {
    const a = analyzeProperty(with_({ ...rental, checklist: { ...rental.checklist, furniture: "not-included" } }), NOW);
    assert.ok(a.tenancy);
    const labels = a.tenancy.moveInCosts.map((c) => c.label);
    assert.ok(labels.includes("Deposit (2 months)"));
    assert.ok(labels.includes("Furniture (basic)"));
    assert.equal(a.tenancy.totalMoveIn, a.tenancy.moveInCosts.reduce((s, c) => s + c.amount, 0));
  });

  it("values a furnished let higher, without changing the sale value", () => {
    const bare = analyzeProperty(with_({ ...rental, checklist: { ...rental.checklist, furniture: "not-included" } }), NOW);
    const furnished = analyzeProperty(with_({ ...rental, checklist: { ...rental.checklist, furniture: "included" } }), NOW);
    assert.ok(furnished.estimate.mid > bare.estimate.mid);
    assert.equal(furnished.propertyValue, bare.propertyValue);
  });

  it("does not charge tenants for the owner's repairs", () => {
    const a = analyzeProperty(with_({ ...rental, condition: "needs-renovation", yearBuilt: 1960 }), NOW);
    assert.equal(a.inspection.find((f) => f.area === "Finishes")?.estimatedCost, 0);
    assert.ok(!a.inspection.some((f) => f.area === "Electrical"));
  });
});
