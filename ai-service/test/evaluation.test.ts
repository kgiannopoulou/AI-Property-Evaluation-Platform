import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { failedCase, gate, scoreCase, summarise, validateCase, type LabelledCase } from "../src/evaluation/index.ts";
import type { Finding, InspectionReport } from "../src/report-generation/index.ts";

const finding = (category: Finding["category"], photoIds: string[], severity: Finding["severity"] = "moderate"): Finding => ({
  id: `${category}-${photoIds.join("-")}`,
  photoIds,
  category,
  categoryLabel: category,
  title: category,
  description: "",
  severity,
  confidence: 0.8,
  assessment: "observed",
  limitation: null,
  recommendation: "",
  estimatedCost: null,
  costNote: null,
});

const report = (findings: Finding[], areaConditions: InspectionReport["areaConditions"] = []): InspectionReport => ({
  propertyId: "case",
  generatedAt: "2026-11-25T00:00:00Z",
  model: "fake",
  servedBy: ["fake"],
  photos: { submitted: 2, analysed: 2, skipped: [] },
  findings,
  areaConditions,
  discardedFindings: 0,
  disclaimer: "",
});

const labelled: LabelledCase = {
  caseId: "flat-1",
  photos: [
    { photoId: "k1", file: "flat-1/k1.jpg", area: "kitchen" },
    { photoId: "b1", file: "flat-1/b1.jpg", area: "bathroom" },
  ],
  expected: {
    findings: [
      { category: "mould-damp", photoIds: ["b1"], severity: "major" },
      { category: "kitchen-condition", severity: "minor" },
    ],
    absent: ["crack"],
    areaConditions: [{ area: "bathroom", condition: "poor" }],
  },
};

describe("AI accuracy evaluation", () => {
  test("a perfect report scores full precision and recall", () => {
    const s = scoreCase(
      labelled,
      report([finding("mould-damp", ["b1"], "critical"), finding("kitchen-condition", ["k1"], "minor")], [
        { area: "bathroom", condition: "fair", summary: "" },
      ]),
    );
    assert.equal(s.truePositives, 2);
    assert.equal(s.falsePositives, 0);
    assert.equal(s.falseNegatives, 0);
    assert.equal(s.severityAgreed, 2, "major vs critical is within one step");
    assert.equal(s.conditionAgreed, 1, "poor vs fair is within one step");
    const sum = summarise([labelled], [s]);
    assert.equal(sum.precision, 1);
    assert.equal(sum.recall, 1);
    assert.deepEqual(gate(sum), []);
  });

  test("a finding on the wrong photo is a miss and a false positive", () => {
    const s = scoreCase(labelled, report([finding("mould-damp", ["k1"]), finding("kitchen-condition", ["k1"])]));
    assert.equal(s.truePositives, 1);
    assert.equal(s.falseNegatives, 1);
    assert.equal(s.falsePositives, 1);
    assert.equal(s.missed[0].category, "mould-damp");
  });

  test("findings in an absent category are false alarms; severity two steps off disagrees", () => {
    const s = scoreCase(
      labelled,
      report([finding("mould-damp", ["b1"], "minor"), finding("kitchen-condition", ["k1"], "minor"), finding("crack", ["k1"])]),
    );
    assert.equal(s.falseAlarms, 1);
    assert.equal(s.falsePositives, 1);
    assert.equal(s.severityAgreed, 1);
    const sum = summarise([labelled], [s]);
    assert.equal(sum.perCategory.crack.fp, 1);
    assert.equal(sum.perCategory["mould-damp"].tp, 1);
    assert.equal(sum.precision, 2 / 3);
    assert.ok(gate(sum).some((r) => r.startsWith("precision")));
  });

  test("one report finding cannot satisfy two expected findings", () => {
    const twice: LabelledCase = {
      ...labelled,
      expected: { findings: [{ category: "mould-damp" }, { category: "mould-damp" }] },
    };
    const s = scoreCase(twice, report([finding("mould-damp", ["b1"])]));
    assert.equal(s.truePositives, 1);
    assert.equal(s.falseNegatives, 1);
  });

  test("a failed inspection counts every expected finding as missed and fails the gate", () => {
    const s = failedCase(labelled, "model unavailable");
    const sum = summarise([labelled], [s]);
    assert.equal(sum.failedCases, 1);
    assert.equal(sum.recall, 0);
    assert.equal(sum.precision, null);
    const reasons = gate(sum);
    assert.ok(reasons.some((r) => r.includes("failed")));
    assert.ok(reasons.some((r) => r.startsWith("precision n/a")), "no evidence is not a pass");
  });

  test("labelled cases are validated", () => {
    assert.ok(validateCase(labelled));
    assert.ok(!validateCase({ ...labelled, photos: [] }));
    assert.ok(!validateCase({ ...labelled, expected: { findings: [{ category: "ghosts" }] } }));
    assert.ok(!validateCase({ ...labelled, expected: { findings: [{ category: "crack", photoIds: ["zz"] }] } }));
    assert.ok(
      !validateCase({ ...labelled, expected: { findings: [{ category: "crack" }], absent: ["crack"] } }),
      "a category cannot be both expected and absent",
    );
  });
});
