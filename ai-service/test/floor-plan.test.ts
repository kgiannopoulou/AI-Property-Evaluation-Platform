import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import { after, before, describe, test } from "node:test";
import Anthropic from "@anthropic-ai/sdk";
import { postProcess } from "../src/floor-plan/index.ts";
import { ClaudeFloorPlanModel, type FloorPlanModel } from "../src/floor-plan/model.ts";
import { FLOOR_PLAN_PROMPT } from "../src/floor-plan/prompt.ts";
import type { FloorPlanResult } from "../src/floor-plan/schema.ts";
import { createApp } from "../src/server.ts";
import { LocalPhotoStore } from "../src/storage/index.ts";
import { VisionError } from "../src/vision/claude.ts";
import { FakeVision, JPEG, photoDir } from "./helpers.ts";

const room = (o: Partial<FloorPlanResult["rooms"][number]>): FloorPlanResult["rooms"][number] => ({
  label: "Room",
  room_type: "bedroom",
  area_sqm: null,
  width_m: null,
  length_m: null,
  printed_text: "",
  confidence: 0.9,
  ...o,
});

const plan = (rooms: FloorPlanResult["rooms"], o: Partial<FloorPlanResult> = {}): FloorPlanResult => ({
  is_floor_plan: true,
  legibility: "clear",
  units: "metres",
  scale_printed: "1:50",
  rooms,
  total_area_sqm: 78,
  total_area_text: "ΣΥΝΟΛΟ 78,00 τ.μ.",
  notes: [],
  ...o,
});

describe("floor plan post-processing", () => {
  test("keeps printed areas, computes area only when both sides are printed, never otherwise", () => {
    const r = postProcess("p1", "m", "m", plan([
      room({ label: "ΥΠΝΟΔ.", area_sqm: 12.4, printed_text: "ΥΠΝΟΔ. 12,40 τ.μ." }),
      room({ label: "Kitchen", room_type: "kitchen", width_m: 3, length_m: 2.5 }),
      room({ label: "WC", room_type: "wc", width_m: 1.2 }),
    ]));
    assert.deepEqual(r.rooms.map((x) => [x.areaSqm, x.areaSource]), [[12.4, "printed"], [7.5, "computed"], [null, "none"]]);
    assert.equal(r.totalAreaPrinted, 78);
  });

  test("drops implausible numbers and flags disagreements and low confidence", () => {
    const r = postProcess("p1", "m", "m", plan([
      room({ area_sqm: 9000 }),
      room({ area_sqm: 20, width_m: 3, length_m: 3 }),
      room({ area_sqm: 10, confidence: 0.3 }),
    ]));
    assert.equal(r.rooms[0].areaSqm, null);
    assert.match(r.rooms[0].warnings[0], /implausible/);
    assert.match(r.rooms[1].warnings[0], /differs from 3 × 3 m/);
    assert.match(r.rooms[2].warnings[0], /Low confidence/);
  });

  test("returns no rooms for an image that is not a floor plan", () => {
    const r = postProcess("p1", "m", "m", plan([room({ area_sqm: 10 })], { is_floor_plan: false }));
    assert.equal(r.isFloorPlan, false);
    assert.deepEqual(r.rooms, []);
  });
});

describe("Claude floor plan adapter", () => {
  test("sends the image with a cached prompt, structured output and server-side fallback", async () => {
    const requests: Record<string, unknown>[] = [];
    const client = {
      beta: {
        messages: {
          parse: async (params: Record<string, unknown>) => {
            requests.push(params);
            return { stop_reason: "end_turn", parsed_output: plan([]), model: "claude-opus-5-5" };
          },
        },
      },
    } as unknown as Anthropic;
    const out = await new ClaudeFloorPlanModel("claude-opus-5-5", client).read({ mediaType: "image/jpeg", data: JPEG });
    assert.equal(out.servedBy, "claude-opus-5-5");
    const req = requests[0] as { system: { text: string; cache_control: unknown }[]; fallbacks: string; betas: string[]; messages: { content: { type: string }[] }[] };
    assert.equal(req.system[0].text, FLOOR_PLAN_PROMPT);
    assert.deepEqual(req.system[0].cache_control, { type: "ephemeral" });
    assert.equal(req.fallbacks, "default");
    assert.deepEqual(req.messages[0].content.map((c) => c.type), ["image", "text"]);
  });

  test("a refusal is reported, not turned into an empty plan", async () => {
    const client = {
      beta: { messages: { parse: async () => ({ stop_reason: "refusal", parsed_output: null, model: "x" }) } },
    } as unknown as Anthropic;
    await assert.rejects(new ClaudeFloorPlanModel("m", client).read({ mediaType: "image/jpeg", data: JPEG }), VisionError);
  });
});

describe("POST /v1/floor-plans", () => {
  const TOKEN = "t";
  let base = "";
  let server: ReturnType<typeof createApp>;
  const fake: FloorPlanModel = {
    id: "fake-plan",
    read: async () => ({ result: plan([room({ area_sqm: 12 })]), servedBy: "fake-plan" }),
  };
  const post = (body: unknown) =>
    fetch(`${base}/v1/floor-plans`, { method: "POST", headers: { authorization: `Bearer ${TOKEN}` }, body: JSON.stringify(body) });

  before(async () => {
    const dir = await photoDir({ "plans/p1/plan.jpg": JPEG, "plans/p1/notes.txt": Buffer.from("not an image") });
    server = createApp({ store: new LocalPhotoStore(dir), vision: new FakeVision(), floorPlans: fake, includeIllustrativeCosts: false, serviceToken: TOKEN });
    await new Promise<void>((resolve) => server.listen(0, resolve));
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });
  after(() => server.close());

  test("reads a stored plan", async () => {
    const res = await post({ planId: "p1", storageKey: "plans/p1/plan.jpg" });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.rooms[0].areaSqm, 12);
    assert.equal(body.servedBy, "fake-plan");
  });

  test("rejects bad keys, missing files and non-images", async () => {
    assert.equal((await post({ planId: "p1", storageKey: "../etc/passwd" })).status, 400);
    assert.equal((await post({ planId: "p1", storageKey: "plans/p1/missing.jpg" })).status, 422);
    assert.equal((await post({ planId: "p1", storageKey: "plans/p1/notes.txt" })).status, 422);
  });
});
