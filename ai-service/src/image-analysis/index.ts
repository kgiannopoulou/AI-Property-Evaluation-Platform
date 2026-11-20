import type { PhotoRef } from "../types.ts";

/** Room classification and photo quality checks before defect detection. Phase 5. */
export type ImageAnalysis = { photo: PhotoRef; room: string | null; usable: boolean; reason?: string };

export function validatePhotos(photos: unknown): photos is PhotoRef[] {
  return (
    Array.isArray(photos) &&
    photos.length > 0 &&
    photos.length <= 20 &&
    photos.every((p) => typeof p === "object" && p !== null && typeof (p as PhotoRef).storageKey === "string")
  );
}
