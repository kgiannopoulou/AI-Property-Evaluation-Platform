import Anthropic from "@anthropic-ai/sdk";
import { betaZodOutputFormat } from "@anthropic-ai/sdk/helpers/beta/zod";
import type { LoadedPhoto } from "../image-analysis/index.ts";
import { batchIntro, SYSTEM_PROMPT, type InspectionContext } from "./prompt.ts";
import { BatchResultSchema, type BatchResult } from "./schema.ts";

/** Anything that can look at a batch of photos and return structured findings. Tests use a fake. */
export interface VisionModel {
  readonly id: string;
  analyse(photos: LoadedPhoto[], context: InspectionContext, batch: number, batches: number): Promise<VisionResult>;
}

export type VisionResult = { result: BatchResult; servedBy: string };

/** The model declined (safety classifier) or could not finish; the caller reports it, never guesses. */
export class VisionError extends Error {
  readonly retryable: boolean;
  constructor(message: string, retryable: boolean) {
    super(message);
    this.retryable = retryable;
  }
}

export class ClaudeVisionModel implements VisionModel {
  readonly id: string;
  private readonly client: Anthropic;

  /** The client resolves credentials itself (ANTHROPIC_API_KEY, or an `ant auth login` profile). */
  constructor(model = "claude-opus-5-5", client: Anthropic = new Anthropic()) {
    this.id = model;
    this.client = client;
  }

  async analyse(
    photos: LoadedPhoto[],
    context: InspectionContext,
    batch: number,
    batches: number,
  ): Promise<VisionResult> {
    const areas = new Set(photos.map((p) => p.area));
    const content: Anthropic.Beta.BetaContentBlockParam[] = [
      { type: "text", text: batchIntro(context, areas, batch, batches) },
    ];
    for (const p of photos) {
      content.push({ type: "text", text: `Photo id=${p.photoId} · area: ${p.area}` });
      content.push({
        type: "image",
        source: { type: "base64", media_type: p.mediaType, data: p.data.toString("base64") },
      });
    }

    let response;
    try {
      response = await this.client.beta.messages.parse({
        model: this.id,
        max_tokens: 16000,
        // If a safety classifier declines, the API retries on a suitable model within the same call.
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
        output_config: { effort: "high", format: betaZodOutputFormat(BatchResultSchema) },
        system: [{ type: "text", text: SYSTEM_PROMPT, cache_control: { type: "ephemeral" } }],
        messages: [{ role: "user", content }],
      });
    } catch (err) {
      if (err instanceof Anthropic.AuthenticationError || err instanceof Anthropic.PermissionDeniedError)
        throw new VisionError("The AI provider rejected the credentials; check ANTHROPIC_API_KEY.", false);
      if (err instanceof Anthropic.BadRequestError) throw new VisionError(`The AI provider rejected the request: ${err.message}`, false);
      if (err instanceof Anthropic.RateLimitError) throw new VisionError("The AI provider is rate limiting requests; try again shortly.", true);
      if (err instanceof Anthropic.APIError) throw new VisionError(`AI provider error (${err.status ?? "network"}).`, true);
      // Anything else failed inside the SDK before a request was sent — in practice missing
      // credentials or invalid client configuration. Log the detail; report a clear message.
      console.error("Vision request could not be sent:", err);
      throw new VisionError(
        "The AI provider is not configured; set ANTHROPIC_API_KEY (or another supported credential) for the ai-service.",
        false,
      );
    }

    if (response.stop_reason === "refusal")
      throw new VisionError("The AI model declined to analyse these photographs.", false);
    if (response.stop_reason === "max_tokens")
      throw new VisionError("The analysis was cut off before it finished; try fewer photographs per request.", true);
    if (!response.parsed_output) throw new VisionError("The AI model returned an unreadable result.", true);

    return { result: response.parsed_output, servedBy: response.model };
  }
}
