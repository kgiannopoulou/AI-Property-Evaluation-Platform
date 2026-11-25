import { FINDING_CATEGORIES, SEVERITIES, type FindingCategory, type Severity } from "../defect-detection/index.ts";
import { PHOTO_AREAS, type PhotoArea } from "../equipment-detection/index.ts";
import type { InspectionReport } from "../report-generation/index.ts";
import { AREA_CONDITIONS, type AreaConditionRating } from "../vision/schema.ts";

/**
 * Accuracy evaluation of the AI Home Inspector against photographs labelled by a person.
 *
 * A labelled case lists the photos of one property and what a qualified reviewer saw in them:
 * the findings that must be reported (category, which photos, how serious), categories that must
 * NOT be reported (known false-alarm traps, e.g. a shadow that looks like a crack) and the
 * condition of each area. The scorer compares the model's report with those labels and the
 * runner (eval/run.ts) gates a model or prompt change on precision and recall.
 *
 * Matching rules:
 * - an expected finding is found when the report has a finding of the same category that shows
 *   at least one of the expected photos (any photo when the label lists none);
 * - every report finding that matches no expected finding is a false positive, and one in an
 *   "absent" category is also counted as a false alarm;
 * - severity agrees when it is within one step (minor vs moderate), and area condition likewise.
 */

export type ExpectedFinding = { category: FindingCategory; photoIds?: string[]; severity?: Severity };

export type LabelledCase = {
  caseId: string;
  photos: { photoId: string; file: string; area: PhotoArea }[];
  expected: {
    findings: ExpectedFinding[];
    absent?: FindingCategory[];
    areaConditions?: { area: PhotoArea; condition: Exclude<AreaConditionRating, "not-assessable"> }[];
  };
  /** Who labelled it and on what basis, e.g. "site survey 2026-05". */
  labelledBy?: string;
};

export type CaseScore = {
  caseId: string;
  truePositives: number;
  falsePositives: number;
  falseNegatives: number;
  falseAlarms: number;
  severityCompared: number;
  severityAgreed: number;
  conditionCompared: number;
  conditionAgreed: number;
  missed: ExpectedFinding[];
  unexpected: { category: FindingCategory; photoIds: string[]; title: string }[];
  /** Set when the inspection failed instead of returning a report. */
  error?: string;
};

export type Rates = { precision: number | null; recall: number | null; f1: number | null };

export type Summary = Rates & {
  cases: number;
  failedCases: number;
  truePositives: number;
  falsePositives: number;
  falseNegatives: number;
  falseAlarms: number;
  severityAgreement: number | null;
  conditionAgreement: number | null;
  perCategory: Record<string, Rates & { tp: number; fp: number; fn: number }>;
};

export type Thresholds = { minPrecision: number; minRecall: number; maxFailedCases: number };

/** Defaults for a release gate; tighten them as the labelled set grows. */
export const DEFAULT_THRESHOLDS: Thresholds = { minPrecision: 0.8, minRecall: 0.7, maxFailedCases: 0 };

const sevRank = (s: Severity) => SEVERITIES.indexOf(s);
const condRank = (c: AreaConditionRating) => AREA_CONDITIONS.indexOf(c);

export function validateCase(raw: unknown): raw is LabelledCase {
  if (typeof raw !== "object" || raw === null) return false;
  const c = raw as LabelledCase;
  if (typeof c.caseId !== "string" || !Array.isArray(c.photos) || !c.photos.length) return false;
  if (!c.photos.every((p) => typeof p.photoId === "string" && typeof p.file === "string" && PHOTO_AREAS.includes(p.area)))
    return false;
  const ids = new Set(c.photos.map((p) => p.photoId));
  if (ids.size !== c.photos.length) return false;
  const e = c.expected;
  if (typeof e !== "object" || e === null || !Array.isArray(e.findings)) return false;
  for (const f of e.findings) {
    if (!FINDING_CATEGORIES.includes(f.category)) return false;
    if (f.severity !== undefined && !SEVERITIES.includes(f.severity)) return false;
    if (f.photoIds !== undefined && !f.photoIds.every((id) => ids.has(id))) return false;
  }
  if (e.absent !== undefined && !e.absent.every((a) => FINDING_CATEGORIES.includes(a))) return false;
  if (e.absent?.some((a) => e.findings.some((f) => f.category === a))) return false;
  if (e.areaConditions !== undefined && !e.areaConditions.every((a) => PHOTO_AREAS.includes(a.area) && AREA_CONDITIONS.includes(a.condition)))
    return false;
  return true;
}

export function scoreCase(c: LabelledCase, report: InspectionReport): CaseScore {
  const used = new Set<number>();
  const score: CaseScore = {
    caseId: c.caseId,
    truePositives: 0,
    falsePositives: 0,
    falseNegatives: 0,
    falseAlarms: 0,
    severityCompared: 0,
    severityAgreed: 0,
    conditionCompared: 0,
    conditionAgreed: 0,
    missed: [],
    unexpected: [],
  };

  for (const exp of c.expected.findings) {
    const i = report.findings.findIndex(
      (f, idx) =>
        !used.has(idx) &&
        f.category === exp.category &&
        (!exp.photoIds?.length || f.photoIds.some((id) => exp.photoIds!.includes(id))),
    );
    if (i < 0) {
      score.falseNegatives++;
      score.missed.push(exp);
      continue;
    }
    used.add(i);
    score.truePositives++;
    if (exp.severity) {
      score.severityCompared++;
      if (Math.abs(sevRank(exp.severity) - sevRank(report.findings[i].severity)) <= 1) score.severityAgreed++;
    }
  }

  const absent = new Set(c.expected.absent ?? []);
  report.findings.forEach((f, idx) => {
    if (used.has(idx)) return;
    score.falsePositives++;
    if (absent.has(f.category)) score.falseAlarms++;
    score.unexpected.push({ category: f.category, photoIds: f.photoIds, title: f.title });
  });

  for (const exp of c.expected.areaConditions ?? []) {
    const got = report.areaConditions.find((a) => a.area === exp.area);
    if (!got || got.condition === "not-assessable") continue;
    score.conditionCompared++;
    if (Math.abs(condRank(exp.condition) - condRank(got.condition)) <= 1) score.conditionAgreed++;
  }
  return score;
}

/** A case whose inspection threw: every expected finding counts as missed. */
export function failedCase(c: LabelledCase, error: string): CaseScore {
  return {
    caseId: c.caseId,
    truePositives: 0,
    falsePositives: 0,
    falseNegatives: c.expected.findings.length,
    falseAlarms: 0,
    severityCompared: 0,
    severityAgreed: 0,
    conditionCompared: 0,
    conditionAgreed: 0,
    missed: c.expected.findings,
    unexpected: [],
    error,
  };
}

function rates(tp: number, fp: number, fn: number): Rates {
  const precision = tp + fp ? tp / (tp + fp) : null;
  const recall = tp + fn ? tp / (tp + fn) : null;
  const f1 = precision !== null && recall !== null && precision + recall > 0 ? (2 * precision * recall) / (precision + recall) : null;
  return { precision, recall, f1 };
}

export function summarise(cases: LabelledCase[], scores: CaseScore[]): Summary {
  const sum = (k: keyof CaseScore) => scores.reduce((n, s) => n + (s[k] as number), 0);
  const tp = sum("truePositives");
  const fp = sum("falsePositives");
  const fn = sum("falseNegatives");
  const sevN = sum("severityCompared");
  const condN = sum("conditionCompared");

  const perCategory: Summary["perCategory"] = {};
  const bump = (cat: string, k: "tp" | "fp" | "fn") => {
    perCategory[cat] ??= { tp: 0, fp: 0, fn: 0, precision: null, recall: null, f1: null };
    perCategory[cat][k]++;
  };
  scores.forEach((s, i) => {
    const c = cases[i];
    const missed = new Set(s.missed);
    for (const exp of c.expected.findings) bump(exp.category, missed.has(exp) ? "fn" : "tp");
    for (const u of s.unexpected) bump(u.category, "fp");
  });
  for (const v of Object.values(perCategory)) Object.assign(v, rates(v.tp, v.fp, v.fn));

  return {
    cases: scores.length,
    failedCases: scores.filter((s) => s.error).length,
    truePositives: tp,
    falsePositives: fp,
    falseNegatives: fn,
    falseAlarms: sum("falseAlarms"),
    ...rates(tp, fp, fn),
    severityAgreement: sevN ? sum("severityAgreed") / sevN : null,
    conditionAgreement: condN ? sum("conditionAgreed") / condN : null,
    perCategory,
  };
}

/** Reasons the summary fails the gate (empty = pass). Undefined rates fail: no evidence is not a pass. */
export function gate(s: Summary, t: Thresholds = DEFAULT_THRESHOLDS): string[] {
  const out: string[] = [];
  if (s.precision === null || s.precision < t.minPrecision)
    out.push(`precision ${fmt(s.precision)} is below ${t.minPrecision}`);
  if (s.recall === null || s.recall < t.minRecall) out.push(`recall ${fmt(s.recall)} is below ${t.minRecall}`);
  if (s.failedCases > t.maxFailedCases) out.push(`${s.failedCases} case(s) failed (allowed ${t.maxFailedCases})`);
  return out;
}

const fmt = (n: number | null) => (n === null ? "n/a" : n.toFixed(2));
