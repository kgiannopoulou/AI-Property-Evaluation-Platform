/**
 * Runs the AI Home Inspector on a labelled photo set and gates on precision and recall.
 *
 *   npm run eval -- <dataset-dir> [--min-precision 0.8] [--min-recall 0.7] [--out results.json]
 *
 * <dataset-dir>/manifest.json is an array of labelled cases (see ./index.ts and eval/README.md);
 * photo files are read relative to the dataset directory. Uses the real model, so it needs
 * ANTHROPIC_API_KEY and costs API credits. Exit code 0 = pass, 1 = gate failed, 2 = setup error.
 */
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { runInspection } from "../inspection.ts";
import { LocalPhotoStore } from "../storage/index.ts";
import { ClaudeVisionModel } from "../vision/claude.ts";
import {
  DEFAULT_THRESHOLDS,
  failedCase,
  gate,
  scoreCase,
  summarise,
  validateCase,
  type CaseScore,
  type LabelledCase,
} from "./index.ts";

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(name);
  return i > 0 ? process.argv[i + 1] : undefined;
}

async function main(): Promise<number> {
  const dir = process.argv[2];
  if (!dir || dir.startsWith("--")) {
    console.error("Usage: npm run eval -- <dataset-dir> [--min-precision n] [--min-recall n] [--out file]");
    return 2;
  }
  if (!process.env.ANTHROPIC_API_KEY) {
    console.error("ANTHROPIC_API_KEY is not set: the evaluation runs the real vision model.");
    return 2;
  }
  const raw: unknown = JSON.parse(await readFile(path.join(dir, "manifest.json"), "utf8"));
  if (!Array.isArray(raw) || !raw.length) {
    console.error("manifest.json must be a non-empty array of labelled cases.");
    return 2;
  }
  const invalid = raw.findIndex((c) => !validateCase(c));
  if (invalid >= 0) {
    console.error(`Case #${invalid + 1} in manifest.json is not a valid labelled case.`);
    return 2;
  }
  const cases = raw as LabelledCase[];
  const model = new ClaudeVisionModel(process.env.AI_VISION_MODEL || "claude-opus-5-5");
  const store = new LocalPhotoStore(path.resolve(dir));

  const scores: CaseScore[] = [];
  for (const c of cases) {
    try {
      const report = await runInspection(
        { propertyId: c.caseId, photos: c.photos.map((p) => ({ photoId: p.photoId, storageKey: p.file, area: p.area })) },
        { store, vision: model, includeIllustrativeCosts: false },
      );
      scores.push(scoreCase(c, report));
    } catch (e) {
      scores.push(failedCase(c, e instanceof Error ? e.message : String(e)));
    }
    const s = scores.at(-1)!;
    console.log(`${c.caseId}: tp ${s.truePositives} fp ${s.falsePositives} fn ${s.falseNegatives}${s.error ? ` ERROR ${s.error}` : ""}`);
  }

  const summary = summarise(cases, scores);
  const thresholds = {
    ...DEFAULT_THRESHOLDS,
    ...(arg("--min-precision") ? { minPrecision: Number(arg("--min-precision")) } : {}),
    ...(arg("--min-recall") ? { minRecall: Number(arg("--min-recall")) } : {}),
  };
  const failures = gate(summary, thresholds);
  const result = { model: model.id, ranAt: new Date().toISOString(), thresholds, summary, cases: scores, passed: !failures.length };
  const out = arg("--out");
  if (out) await writeFile(out, JSON.stringify(result, null, 2));

  const pct = (n: number | null) => (n === null ? "n/a" : `${(n * 100).toFixed(1)}%`);
  console.log(
    `\n${summary.cases} cases · precision ${pct(summary.precision)} · recall ${pct(summary.recall)} · F1 ${pct(summary.f1)}` +
      ` · severity agreement ${pct(summary.severityAgreement)} · false alarms ${summary.falseAlarms} · failed ${summary.failedCases}`,
  );
  if (failures.length) console.log(`FAILED: ${failures.join("; ")}`);
  else console.log("PASSED");
  return failures.length ? 1 : 0;
}

process.exitCode = await main();
