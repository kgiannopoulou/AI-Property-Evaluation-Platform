import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { applySafetyRules, type ModelFinding } from "../src/defect-detection/index.ts";
import { describeDeclaredEquipment } from "../src/equipment-detection/index.ts";
import { batchPhotos, LIMITS, loadPhotos, sniffMediaType, validatePhotos, type LoadedPhoto } from "../src/image-analysis/index.ts";
import { runInspection } from "../src/inspection.ts";
import { buildReport } from "../src/report-generation/index.ts";
import { isSafeStorageKey, LocalPhotoStore, PhotoNotFoundError, SupabasePhotoStore } from "../src/storage/index.ts";
import { FakeVision, JPEG, PNG, photoDir } from "./helpers.ts";

const finding = (o: Partial<ModelFinding> = {}): ModelFinding => ({
  photoIds: ["p1"],
  category: "flooring",
  title: "Chipped tiles",
  description: "Two chipped floor tiles near the door.",
  severity: "minor",
  confidence: 0.9,
  assessment: "observed",
  recommendation: "Replace the tiles.",
  ...o,
});

describe("safety rules", () => {
  test("hidden-risk categories are always potential concerns with a fixed limitation", () => {
    for (const category of ["mould-damp", "water-stain", "crack", "plumbing", "electrical"] as const) {
      const f = applySafetyRules(finding({ category, assessment: "observed", confidence: 1 }));
      assert.equal(f.assessment, "potential-concern", category);
      assert.ok(f.confidence <= 0.85, category);
      assert.ok(f.limitation, category);
    }
  });

  test("diagnostic wording is softened in potential concerns", () => {
    const f = applySafetyRules(
      finding({ category: "crack", title: "Crack is structural", description: "This is definitely a structural failure." }),
    );
    assert.equal(f.title, "Possible: crack may be structural");
    assert.equal(f.description, "This may indicate a structural problem.");
    const leak = applySafetyRules(
      finding({ category: "water-stain", title: "Water stain", description: "Confirmed active leak under the sink." }),
    );
    assert.equal(leak.title, "Possible: water stain");
    assert.equal(leak.description, "A possible leak under the sink.");
    const hedged = applySafetyRules(finding({ category: "electrical", title: "Signs of scorching at a socket" }));
    assert.equal(hedged.title, "Signs of scorching at a socket", "already-hedged titles are left alone");
  });

  test("plainly visible issues stay observed and keep their confidence", () => {
    const f = applySafetyRules(finding());
    assert.equal(f.assessment, "observed");
    assert.equal(f.confidence, 0.9);
    assert.equal(f.limitation, null);
  });

  test("confidence is clamped to 0-1", () => {
    assert.equal(applySafetyRules(finding({ confidence: 7 })).confidence, 1);
    assert.equal(applySafetyRules(finding({ confidence: -1 })).confidence, 0);
    assert.equal(applySafetyRules(finding({ confidence: Number.NaN })).confidence, 0);
  });
});

describe("photos", () => {
  test("media type comes from the bytes", () => {
    assert.equal(sniffMediaType(JPEG), "image/jpeg");
    assert.equal(sniffMediaType(PNG), "image/png");
    assert.equal(sniffMediaType(Buffer.from("RIFF0000WEBPxxxx")), "image/webp");
    assert.equal(sniffMediaType(Buffer.from("%PDF-1.7")), null);
  });

  test("request validation", () => {
    const ok = { photoId: "a", storageKey: "k", area: "kitchen" };
    assert.ok(validatePhotos([ok]));
    assert.ok(!validatePhotos([]));
    assert.ok(!validatePhotos([{ ...ok, photoId: "has space" }]));
    assert.ok(!validatePhotos(Array.from({ length: LIMITS.maxPhotos + 1 }, (_, i) => ({ ...ok, photoId: `p${i}` }))));
  });

  test("oversized and non-image files are skipped with a reason", async () => {
    const dir = await photoDir({ "a.jpg": JPEG, "b.pdf": Buffer.from("%PDF-1.7"), "c.jpg": Buffer.concat([JPEG, Buffer.alloc(LIMITS.maxImageBytes)]) });
    const { loaded, skipped } = await loadPhotos(
      [
        { photoId: "a", storageKey: "a.jpg", area: "kitchen" },
        { photoId: "b", storageKey: "b.pdf", area: "kitchen" },
        { photoId: "c", storageKey: "c.jpg", area: "kitchen" },
      ],
      new LocalPhotoStore(dir),
    );
    assert.deepEqual(loaded.map((p) => p.photoId), ["a"]);
    assert.deepEqual(skipped.map((s) => s.photoId), ["b", "c"]);
  });

  test("batches keep areas together and respect size and count limits", () => {
    const mk = (id: string, area: LoadedPhoto["area"], bytes = 10): LoadedPhoto => ({
      photoId: id, storageKey: id, area, mediaType: "image/jpeg", data: Buffer.alloc(bytes),
    });
    const many = Array.from({ length: 14 }, (_, i) => mk(`k${i}`, i % 2 ? "kitchen" : "bathroom"));
    const batches = batchPhotos(many);
    assert.equal(batches.length, 2);
    assert.ok(batches.every((b) => b.length <= LIMITS.maxPhotosPerBatch));
    assert.deepEqual([...new Set(batches[0].slice(0, 7).map((p) => p.area))], ["kitchen"]);

    const big = [mk("a", "kitchen", 10 * 1024 * 1024), mk("b", "kitchen", 10 * 1024 * 1024)];
    assert.equal(batchPhotos(big).length, 2, "two 10 MB photos do not share a request");
  });

  test("all batches are analysed and merged into one report", async () => {
    const files: Record<string, Buffer> = {};
    const refs = Array.from({ length: 15 }, (_, i) => {
      files[`p/${i}.jpg`] = JPEG;
      return { photoId: `p${i}`, storageKey: `p/${i}.jpg`, area: "living-room" as const };
    });
    const vision = new FakeVision((photos) => ({
      findings: [{ ...{ photo_ids: [photos[0].photoId], category: "interior-condition", title: "Scuffed walls", description: "Scuffs.", severity: "minor", confidence: 0.7, assessment: "observed", recommendation: "Repaint." } }],
      area_conditions: [{ area: "living-room", condition: photos.length > 5 ? "good" : "fair", summary: "" }],
      unusable_photos: [],
    }));
    const report = await runInspection({ propertyId: "x", photos: refs }, { store: new LocalPhotoStore(await photoDir(files)), vision, includeIllustrativeCosts: false });
    assert.equal(vision.calls.length, 2);
    assert.deepEqual(vision.calls.map((c) => [c.batch, c.batches]), [[1, 2], [2, 2]]);
    assert.equal(report.findings.length, 2);
    assert.equal(report.areaConditions[0].condition, "fair", "worst rating wins across batches");
  });
});

describe("storage", () => {
  test("rejects keys that could escape the photo directory", () => {
    for (const bad of ["../x.jpg", "a/../../x", "/etc/passwd", "a//b", "C:\\x", ""]) assert.ok(!isSafeStorageKey(bad), bad);
    assert.ok(isSafeStorageKey("properties/4cce/abc-1.jpg"));
  });

  test("local store refuses traversal", async () => {
    await assert.rejects(new LocalPhotoStore(await photoDir({})).read("../secret"), PhotoNotFoundError);
  });

  test("supabase store reads private objects with the service key", async () => {
    let seen: { url: string; auth: string | null } | null = null;
    const fakeFetch = (async (url: string, init: RequestInit) => {
      seen = { url, auth: new Headers(init.headers).get("authorization") };
      return new Response(JPEG, { status: 200 });
    }) as unknown as typeof fetch;
    const store = new SupabasePhotoStore("https://x.supabase.co/", "service-key", "property-photos", fakeFetch);
    assert.deepEqual(await store.read("properties/p 1/a.jpg").catch(() => null), null, "spaces are not valid keys");
    const data = await store.read("properties/p1/a.jpg");
    assert.ok(data.equals(JPEG));
    assert.deepEqual(seen, {
      url: "https://x.supabase.co/storage/v1/object/property-photos/properties/p1/a.jpg",
      auth: "Bearer service-key",
    });
  });
});

describe("report", () => {
  const base = {
    propertyId: "p",
    model: "m",
    submitted: 1,
    analysedIds: new Set(["p1"]),
    skipped: [],
  };
  const result = (severity: "minor" | "moderate", category: "flooring" | "windows" | "poor-maintenance") => ({
    photo_ids: ["p1"], category, title: "t", description: "d", severity, confidence: 0.6, assessment: "observed" as const, recommendation: "r",
  });

  test("illustrative costs appear only when enabled, and are labelled", () => {
    const results = [{ servedBy: "m", result: { findings: [result("moderate", "windows")], area_conditions: [], unusable_photos: [] } }];
    assert.equal(buildReport({ ...base, results, includeIllustrativeCosts: false }).findings[0].estimatedCost, null);
    const withCosts = buildReport({ ...base, results, includeIllustrativeCosts: true }).findings[0];
    assert.equal(withCosts.estimatedCost?.basis, "illustrative");
    assert.ok(withCosts.estimatedCost!.low < withCosts.estimatedCost!.high);
    assert.match(withCosts.costNote ?? "", /Illustrative/);
  });

  test("no cost is invented where the table has no entry", () => {
    const results = [{ servedBy: "m", result: { findings: [result("minor", "poor-maintenance")], area_conditions: [], unusable_photos: [] } }];
    const f = buildReport({ ...base, results, includeIllustrativeCosts: true }).findings[0];
    assert.equal(f.estimatedCost, null);
    assert.match(f.costNote ?? "", /No reliable local cost data/);
  });

  test("findings are ordered by severity", () => {
    const results = [{ servedBy: "m", result: { findings: [result("minor", "flooring"), result("moderate", "windows")], area_conditions: [], unusable_photos: [] } }];
    assert.deepEqual(buildReport({ ...base, results, includeIllustrativeCosts: false }).findings.map((f) => f.severity), ["moderate", "minor"]);
  });
});

describe("equipment context", () => {
  test("items whose area was not photographed are never reported missing", () => {
    const text = describeDeclaredEquipment({ dishwasher: "working", furniture: "included" }, new Set(["kitchen"]));
    assert.match(text, /dishwasher: declared "working" \(would be visible in: kitchen\)/);
    assert.match(text, /furniture: .*not photographed, so do not report it as missing/);
  });
});
