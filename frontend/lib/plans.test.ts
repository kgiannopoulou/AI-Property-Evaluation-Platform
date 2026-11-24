import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { PLANS, formatPlanPrice, planInfo } from "./plans.ts";

describe("plans", () => {
  it("mirror the backend catalog (Phase 9 hypotheses)", () => {
    assert.deepEqual(
      PLANS.map((p) => [p.id, p.monthlyPriceCents, p.analysesPerMonth]),
      [
        ["free", 0, 2],
        ["premium", 999, 20],
        ["professional", 2999, 100],
        ["business", null, null],
      ],
    );
  });

  it("formats prices", () => {
    assert.deepEqual(PLANS.map(formatPlanPrice), ["€0", "€9.99", "€29.99", "Custom"]);
  });

  it("falls back to Free for an unknown id", () => {
    assert.equal(planInfo("nope" as never).id, "free");
  });
});
