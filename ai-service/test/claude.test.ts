import assert from "node:assert/strict";
import { describe, test } from "node:test";
import Anthropic from "@anthropic-ai/sdk";
import type { LoadedPhoto } from "../src/image-analysis/index.ts";
import { ClaudeVisionModel, VisionError } from "../src/vision/claude.ts";
import { SYSTEM_PROMPT } from "../src/vision/prompt.ts";
import { emptyResult, JPEG } from "./helpers.ts";

/** Stands in for the SDK client: captures the request, returns a canned response. */
function fakeClient(response: Record<string, unknown> | (() => never)) {
  const requests: Record<string, unknown>[] = [];
  const client = {
    beta: {
      messages: {
        parse: async (params: Record<string, unknown>) => {
          requests.push(params);
          if (typeof response === "function") response();
          return response;
        },
      },
    },
  } as unknown as Anthropic;
  return { client, requests };
}

const photos: LoadedPhoto[] = [
  { photoId: "k1", storageKey: "k1", area: "kitchen", mediaType: "image/jpeg", data: JPEG },
  { photoId: "b1", storageKey: "b1", area: "bathroom", mediaType: "image/jpeg", data: JPEG },
];

describe("Claude vision adapter", () => {
  test("sends every photo with its id and area, a cached system prompt and a structured-output schema", async () => {
    const { client, requests } = fakeClient({ stop_reason: "end_turn", parsed_output: emptyResult(), model: "claude-opus-5-5" });
    const out = await new ClaudeVisionModel("claude-opus-5-5", client).analyse(photos, { listingType: "rent" }, 1, 1);
    assert.equal(out.servedBy, "claude-opus-5-5");

    const req = requests[0] as {
      model: string;
      fallbacks: string;
      betas: string[];
      output_config: { effort: string; format: { type: string } };
      system: { text: string; cache_control: unknown }[];
      messages: { content: { type: string; text?: string; source?: { media_type: string; data: string } }[] }[];
    };
    assert.equal(req.model, "claude-opus-5-5");
    assert.equal(req.fallbacks, "default");
    assert.deepEqual(req.betas, ["server-side-fallback-2026-07-01"]);
    assert.equal(req.output_config.effort, "high");
    assert.equal(req.output_config.format.type, "json_schema");
    assert.equal(req.system[0].text, SYSTEM_PROMPT);
    assert.deepEqual(req.system[0].cache_control, { type: "ephemeral" });

    const content = req.messages[0].content;
    assert.match(content[0].text!, /considering renting/);
    assert.equal(content[1].text, "Photo id=k1 · area: kitchen");
    assert.equal(content[2].type, "image");
    assert.equal(content[2].source!.media_type, "image/jpeg");
    assert.equal(content[2].source!.data, JPEG.toString("base64"));
    assert.equal(content.filter((c) => c.type === "image").length, 2);
  });

  test("the prompt forbids diagnoses the photos cannot support", () => {
    assert.match(SYSTEM_PROMPT, /cannot be determined from photos/);
    assert.match(SYSTEM_PROMPT, /potential-concern/);
    assert.match(SYSTEM_PROMPT, /Do not give prices/);
  });

  test("a refusal is an error, not an empty report", async () => {
    const { client } = fakeClient({ stop_reason: "refusal", parsed_output: null, model: "m" });
    await assert.rejects(new ClaudeVisionModel("m", client).analyse(photos, {}, 1, 1), (e: unknown) => e instanceof VisionError && !e.retryable);
  });

  test("a truncated answer is a retryable error", async () => {
    const { client } = fakeClient({ stop_reason: "max_tokens", parsed_output: null, model: "m" });
    await assert.rejects(new ClaudeVisionModel("m", client).analyse(photos, {}, 1, 1), (e: unknown) => e instanceof VisionError && e.retryable);
  });

  test("provider errors map to VisionError", async () => {
    const { client } = fakeClient(() => {
      throw new Anthropic.RateLimitError(429, undefined, "slow down", new Headers());
    });
    await assert.rejects(new ClaudeVisionModel("m", client).analyse(photos, {}, 1, 1), (e: unknown) => e instanceof VisionError && e.retryable);
  });
});
