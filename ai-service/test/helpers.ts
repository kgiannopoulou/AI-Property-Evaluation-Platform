import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import type { LoadedPhoto } from "../src/image-analysis/index.ts";
import type { InspectionContext } from "../src/vision/prompt.ts";
import type { BatchResult } from "../src/vision/schema.ts";
import type { VisionModel, VisionResult } from "../src/vision/claude.ts";

/** Smallest byte sequences the media-type sniffer accepts. */
export const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(64, 1)]);
export const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(64, 1)]);

export async function photoDir(files: Record<string, Buffer>): Promise<string> {
  const dir = await mkdtemp(path.join(tmpdir(), "ai-photos-"));
  for (const [key, data] of Object.entries(files)) {
    await mkdir(path.dirname(path.join(dir, key)), { recursive: true });
    await writeFile(path.join(dir, key), data);
  }
  return dir;
}

export const emptyResult = (): BatchResult => ({ findings: [], area_conditions: [], unusable_photos: [] });

/** Records each call and answers from a function, standing in for the vision API. */
export class FakeVision implements VisionModel {
  readonly id = "fake-vision";
  calls: { photos: LoadedPhoto[]; context: InspectionContext; batch: number; batches: number }[] = [];
  answer: (photos: LoadedPhoto[]) => BatchResult | Promise<BatchResult>;
  constructor(answer: (photos: LoadedPhoto[]) => BatchResult | Promise<BatchResult> = emptyResult) {
    this.answer = answer;
  }
  async analyse(photos: LoadedPhoto[], context: InspectionContext, batch: number, batches: number): Promise<VisionResult> {
    this.calls.push({ photos, context, batch, batches });
    return { result: await this.answer(photos), servedBy: this.id };
  }
}
