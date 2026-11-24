import Anthropic from "@anthropic-ai/sdk";
import { betaZodOutputFormat } from "@anthropic-ai/sdk/helpers/beta/zod";
import type { MediaType } from "../image-analysis/index.ts";
import { VisionError } from "../vision/claude.ts";
import { FLOOR_PLAN_PROMPT } from "./prompt.ts";
import { FloorPlanResultSchema, type FloorPlanResult } from "./schema.ts";

/** Anything that can transcribe a floor plan image. Tests use a fake. */
export interface FloorPlanModel {
  readonly id: string;
  read(image: { mediaType: MediaType; data: Buffer }): Promise<{ result: FloorPlanResult; servedBy: string }>;
}

export class ClaudeFloorPlanModel implements FloorPlanModel {
  readonly id: string;
  private readonly client: Anthropic;

  /** The client resolves credentials itself (ANTHROPIC_API_KEY, or an `ant auth login` profile). */
  constructor(model = "claude-opus-5-5", client: Anthropic = new Anthropic()) {
    this.id = model;
    this.client = client;
  }

  async read(image: { mediaType: MediaType; data: Buffer }) {
    let response;
    try {
      response = await this.client.beta.messages.parse({
        model: this.id,
        max_tokens: 16000,
        // If a safety classifier declines, the API retries on a suitable model within the same call.
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
        output_config: { effort: "high", format: betaZodOutputFormat(FloorPlanResultSchema) },
        system: [{ type: "text", text: FLOOR_PLAN_PROMPT, cache_control: { type: "ephemeral" } }],
        messages: [
          {
            role: "user",
            content: [
              { type: "image", source: { type: "base64", media_type: image.mediaType, data: image.data.toString("base64") } },
              { type: "text", text: "Transcribe the rooms and printed measurements of this floor plan." },
            ],
          },
        ],
      });
    } catch (err) {
      if (err instanceof Anthropic.AuthenticationError || err instanceof Anthropic.PermissionDeniedError)
        throw new VisionError("The AI provider rejected the credentials; check ANTHROPIC_API_KEY.", false);
      if (err instanceof Anthropic.BadRequestError) throw new VisionError(`The AI provider rejected the request: ${err.message}`, false);
      if (err instanceof Anthropic.RateLimitError) throw new VisionError("The AI provider is rate limiting requests; try again shortly.", true);
      if (err instanceof Anthropic.APIError) throw new VisionError(`AI provider error (${err.status ?? "network"}).`, true);
      console.error("Floor plan request could not be sent:", err);
      throw new VisionError("The AI provider is not configured; set ANTHROPIC_API_KEY for the ai-service.", false);
    }
    if (response.stop_reason === "refusal") throw new VisionError("The AI model declined to read this image.", false);
    if (response.stop_reason === "max_tokens") throw new VisionError("The reading was cut off before it finished.", true);
    if (!response.parsed_output) throw new VisionError("The AI model returned an unreadable result.", true);
    return { result: response.parsed_output, servedBy: response.model };
  }
}
