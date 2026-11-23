import { readFile } from "node:fs/promises";
import path from "node:path";

/**
 * Where uploaded photographs live. The backend writes them; this service only
 * reads them by storage key, so no image bytes travel through the browser or
 * the backend → AI request.
 */
export interface PhotoStore {
  read(storageKey: string): Promise<Buffer>;
}

export class PhotoNotFoundError extends Error {}

/** Storage keys look like "properties/<id>/<file>.jpg": no "..", no absolute paths. */
export function isSafeStorageKey(key: string): boolean {
  return (
    key.length > 0 &&
    key.length <= 300 &&
    /^[A-Za-z0-9][A-Za-z0-9._\-/]*$/.test(key) &&
    !key.split("/").some((part) => part === "" || part === "." || part === "..")
  );
}

/** Local directory shared with the backend (development, tests, single-host deployments). */
export class LocalPhotoStore implements PhotoStore {
  private readonly root: string;
  constructor(root: string) {
    this.root = path.resolve(root);
  }

  async read(storageKey: string): Promise<Buffer> {
    if (!isSafeStorageKey(storageKey)) throw new PhotoNotFoundError(`Invalid storage key: ${storageKey}`);
    const file = path.resolve(this.root, storageKey);
    if (!file.startsWith(this.root + path.sep)) throw new PhotoNotFoundError(`Invalid storage key: ${storageKey}`);
    try {
      return await readFile(file);
    } catch {
      throw new PhotoNotFoundError(`Photo not found: ${storageKey}`);
    }
  }
}

/** Supabase Storage (private bucket), read with the service-role key over its REST API. */
export class SupabasePhotoStore implements PhotoStore {
  private readonly url: string;
  private readonly serviceKey: string;
  private readonly bucket: string;
  private readonly fetchImpl: typeof fetch;
  constructor(url: string, serviceKey: string, bucket: string, fetchImpl: typeof fetch = fetch) {
    this.url = url;
    this.serviceKey = serviceKey;
    this.bucket = bucket;
    this.fetchImpl = fetchImpl;
  }

  async read(storageKey: string): Promise<Buffer> {
    if (!isSafeStorageKey(storageKey)) throw new PhotoNotFoundError(`Invalid storage key: ${storageKey}`);
    const objectPath = storageKey.split("/").map(encodeURIComponent).join("/");
    const res = await this.fetchImpl(
      `${this.url.replace(/\/$/, "")}/storage/v1/object/${encodeURIComponent(this.bucket)}/${objectPath}`,
      { headers: { authorization: `Bearer ${this.serviceKey}`, apikey: this.serviceKey } },
    );
    if (res.status === 404 || res.status === 400) throw new PhotoNotFoundError(`Photo not found: ${storageKey}`);
    if (!res.ok) throw new Error(`Storage returned ${res.status} for ${storageKey}`);
    return Buffer.from(await res.arrayBuffer());
  }
}
