import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import { after, before, describe, test } from "node:test";
import { createApp } from "../src/server.ts";
import { LocalPhotoStore } from "../src/storage/index.ts";
import { VisionError } from "../src/vision/claude.ts";
import { FakeVision, JPEG, PNG, photoDir } from "./helpers.ts";

const TOKEN = "test-token";
const vision = new FakeVision();
let base = "";
let server: ReturnType<typeof createApp>;

const post = (body: unknown, token: string | null = TOKEN) =>
  fetch(`${base}/v1/inspections`, {
    method: "POST",
    headers: token ? { authorization: `Bearer ${token}` } : {},
    body: typeof body === "string" ? body : JSON.stringify(body),
  });

const photos = [
  { photoId: "k1", storageKey: "properties/p1/k1.jpg", area: "kitchen" },
  { photoId: "b1", storageKey: "properties/p1/b1.png", area: "bathroom" },
];

before(async () => {
  const dir = await photoDir({ "properties/p1/k1.jpg": JPEG, "properties/p1/b1.png": PNG });
  server = createApp({ store: new LocalPhotoStore(dir), vision, includeIllustrativeCosts: false, serviceToken: TOKEN });
  await new Promise<void>((resolve) => server.listen(0, resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
after(() => server.close());

describe("ai-service HTTP API", () => {
  test("health is public", async () => {
    const res = await fetch(`${base}/health`);
    assert.equal(res.status, 200);
    assert.equal((await res.json()).status, "UP");
  });

  test("v1 endpoints need the service token", async () => {
    assert.equal((await post({ propertyId: "p1", photos }, null)).status, 401);
    assert.equal((await post({ propertyId: "p1", photos }, "wrong")).status, 401);
  });

  test("capabilities list the categories and which are potential-concern only", async () => {
    const res = await fetch(`${base}/v1/capabilities`, { headers: { authorization: `Bearer ${TOKEN}` } });
    const body = await res.json();
    assert.equal(body.visionModel, "fake-vision");
    assert.equal(body.categories.length, 13);
    assert.equal(body.categories.find((c: { id: string }) => c.id === "electrical").potentialConcernOnly, true);
    assert.equal(body.categories.find((c: { id: string }) => c.id === "flooring").potentialConcernOnly, false);
  });

  test("validates the request", async () => {
    assert.equal((await post({ photos })).status, 400);
    assert.equal((await post({ propertyId: "p1", photos: [] })).status, 400);
    assert.equal((await post({ propertyId: "p1", photos: [{ ...photos[0], area: "garage" }] })).status, 400);
    assert.equal((await post({ propertyId: "p1", photos: [photos[0], photos[0]] })).status, 400); // duplicate id
    assert.equal((await post("{")).status, 400);
  });

  test("returns structured findings that reference the photographs", async () => {
    vision.answer = () => ({
      findings: [
        {
          photo_ids: ["b1"],
          category: "mould-damp",
          title: "Confirmed mould on ceiling",
          description: "Dark spotting in the ceiling corner above the shower.",
          severity: "moderate",
          confidence: 0.97,
          assessment: "observed",
          recommendation: "Ask for a moisture survey.",
        },
        {
          photo_ids: ["k1", "not-a-photo"],
          category: "kitchen-condition",
          title: "Worn kitchen worktop",
          description: "Chipped laminate along the front edge of the worktop.",
          severity: "minor",
          confidence: 0.8,
          assessment: "observed",
          recommendation: "Budget for a new worktop.",
        },
        {
          photo_ids: ["x9"],
          category: "crack",
          title: "Invented",
          description: "References a photo that was never sent.",
          severity: "major",
          confidence: 0.5,
          assessment: "potential-concern",
          recommendation: "-",
        },
      ],
      area_conditions: [{ area: "kitchen", condition: "fair", summary: "Dated but usable." }],
      unusable_photos: [],
    });
    const res = await post({
      propertyId: "p1",
      photos,
      context: { listingType: "sale", declaredChecklist: { refrigerator: "working", bogus: "x" } },
    });
    assert.equal(res.status, 200);
    const report = await res.json();

    assert.equal(report.photos.analysed, 2);
    assert.equal(report.findings.length, 2);
    assert.equal(report.discardedFindings, 1, "finding with unknown photo references is dropped");

    const damp = report.findings[0];
    assert.deepEqual(damp.photoIds, ["b1"]);
    assert.equal(damp.assessment, "potential-concern", "mould is never a confirmed diagnosis");
    assert.equal(damp.confidence, 0.85, "potential concerns are capped below certainty");
    assert.doesNotMatch(damp.title, /confirmed/i);
    assert.match(damp.limitation, /cannot show how far moisture has spread/);
    assert.equal(damp.estimatedCost, null, "illustrative costs are off by default");
    assert.match(damp.costNote, /No reliable local cost data/);

    assert.deepEqual(report.findings[1].photoIds, ["k1"], "unknown references are removed");
    assert.match(report.disclaimer, /cannot be established from photos/);

    const call = vision.calls.at(-1)!;
    assert.deepEqual(call.context.declaredChecklist, { refrigerator: "working" }, "only known checklist items pass through");
  });

  test("reports photos that could not be read", async () => {
    vision.answer = () => ({ findings: [], area_conditions: [], unusable_photos: [{ photo_id: "k1", reason: "Too dark" }] });
    const res = await post({
      propertyId: "p1",
      photos: [...photos, { photoId: "gone", storageKey: "properties/p1/gone.jpg", area: "other" }],
    });
    const report = await res.json();
    assert.equal(report.photos.submitted, 3);
    assert.equal(report.photos.analysed, 1);
    assert.deepEqual(
      report.photos.skipped.map((s: { photoId: string }) => s.photoId).sort(),
      ["gone", "k1"],
    );
  });

  test("422 when no photo can be loaded", async () => {
    const res = await post({ propertyId: "p1", photos: [{ photoId: "a", storageKey: "../../etc/passwd", area: "other" }] });
    assert.equal(res.status, 422);
  });

  test("model failures are reported, never replaced with made-up findings", async () => {
    vision.answer = () => {
      throw new VisionError("The AI model declined to analyse these photographs.", false);
    };
    const res = await post({ propertyId: "p1", photos });
    assert.equal(res.status, 502);
    assert.match((await res.json()).error, /declined/);

    vision.answer = () => {
      throw new VisionError("rate limited", true);
    };
    assert.equal((await post({ propertyId: "p1", photos })).status, 503);
  });
});
