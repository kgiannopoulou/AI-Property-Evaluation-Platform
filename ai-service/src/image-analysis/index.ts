import { PHOTO_AREAS, type PhotoArea } from "../equipment-detection/index.ts";
import { PhotoNotFoundError, type PhotoStore } from "../storage/index.ts";

export type PhotoRef = {
  /** Stable id the findings refer back to (the backend's image id). */
  photoId: string;
  /** Object-storage key, never a public URL. */
  storageKey: string;
  area: PhotoArea;
};

export type MediaType = "image/jpeg" | "image/png" | "image/webp" | "image/gif";

export type LoadedPhoto = PhotoRef & { mediaType: MediaType; data: Buffer };
export type SkippedPhoto = { photoId: string; reason: string };

export const LIMITS = {
  maxPhotos: 24,
  /** The vision API rejects images over 5 MB. */
  maxImageBytes: 5 * 1024 * 1024,
  /** Keep each request well under the 32 MB request limit once base64-encoded (+33%). */
  maxBatchBytes: 18 * 1024 * 1024,
  maxPhotosPerBatch: 12,
} as const;

export function validatePhotos(photos: unknown): photos is PhotoRef[] {
  if (!Array.isArray(photos) || photos.length === 0 || photos.length > LIMITS.maxPhotos) return false;
  const ids = new Set<string>();
  for (const p of photos) {
    if (typeof p !== "object" || p === null) return false;
    const { photoId, storageKey, area } = p as Record<string, unknown>;
    if (typeof photoId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(photoId) || ids.has(photoId)) return false;
    if (typeof storageKey !== "string" || storageKey.length === 0) return false;
    if (!PHOTO_AREAS.includes(area as PhotoArea)) return false;
    ids.add(photoId);
  }
  return true;
}

/** Media type from the file's magic bytes — the extension or upload header is not trusted. */
export function sniffMediaType(data: Buffer): MediaType | null {
  if (data.length >= 3 && data[0] === 0xff && data[1] === 0xd8 && data[2] === 0xff) return "image/jpeg";
  if (data.length >= 8 && data.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])))
    return "image/png";
  if (data.length >= 12 && data.toString("ascii", 0, 4) === "RIFF" && data.toString("ascii", 8, 12) === "WEBP")
    return "image/webp";
  if (data.length >= 6 && /^GIF8[79]a$/.test(data.toString("ascii", 0, 6))) return "image/gif";
  return null;
}

/** Reads every photo; unusable ones are reported, not silently dropped. */
export async function loadPhotos(
  photos: PhotoRef[],
  store: PhotoStore,
): Promise<{ loaded: LoadedPhoto[]; skipped: SkippedPhoto[] }> {
  const results = await Promise.all(
    photos.map(async (p): Promise<LoadedPhoto | SkippedPhoto> => {
      let data: Buffer;
      try {
        data = await store.read(p.storageKey);
      } catch (err) {
        if (err instanceof PhotoNotFoundError) return { photoId: p.photoId, reason: "Photo not found in storage" };
        throw err;
      }
      const mediaType = sniffMediaType(data);
      if (!mediaType) return { photoId: p.photoId, reason: "Not a JPEG, PNG, WebP or GIF image" };
      if (data.length > LIMITS.maxImageBytes)
        return { photoId: p.photoId, reason: "Larger than 5 MB; upload a smaller version" };
      return { ...p, mediaType, data };
    }),
  );
  return {
    loaded: results.filter((r): r is LoadedPhoto => "data" in r),
    skipped: results.filter((r): r is SkippedPhoto => !("data" in r)),
  };
}

/**
 * Splits photos into requests. Photos of the same area stay together where
 * possible, so the model can compare them (e.g. two kitchen angles before
 * saying an appliance is missing).
 */
export function batchPhotos(photos: LoadedPhoto[]): LoadedPhoto[][] {
  const ordered = [...photos].sort((a, b) => PHOTO_AREAS.indexOf(a.area) - PHOTO_AREAS.indexOf(b.area));
  const batches: LoadedPhoto[][] = [];
  let current: LoadedPhoto[] = [];
  let bytes = 0;
  for (const p of ordered) {
    if (current.length && (current.length >= LIMITS.maxPhotosPerBatch || bytes + p.data.length > LIMITS.maxBatchBytes)) {
      batches.push(current);
      current = [];
      bytes = 0;
    }
    current.push(p);
    bytes += p.data.length;
  }
  if (current.length) batches.push(current);
  return batches;
}
