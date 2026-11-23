"use client";

import { useRef, useState, type DragEvent } from "react";
import { IconUpload, IconX } from "@/components/icons";
import { cn } from "@/lib/format";
import { LIMITS } from "./submission";
import { PHOTO_AREAS, PHOTO_AREA_LABELS, type PhotoArea, type PropertyPhoto } from "./types";

const MAX_FILE_BYTES = 15 * 1024 * 1024;
const ACCEPTED = ["image/jpeg", "image/png", "image/webp"];

const AREA_HINTS: Record<PhotoArea, string> = {
  kitchen: "Cabinets, worktops, appliances, under the sink",
  bathroom: "Shower or bath, tiles, ceiling, ventilation",
  bedroom: "Each bedroom, windows and walls",
  "living-room": "Whole room, windows, floor, ceiling corners",
  exterior: "Facade, balconies, roof, view from the street",
  "building-entrance": "Entrance, staircase, elevator, mailboxes",
  other: "Storage, parking, damage, anything else",
};

/**
 * Downscale an image in the browser to a ~640px JPEG. While data lives in
 * localStorage, originals are never stored; the backend will upload originals
 * to object storage instead.
 */
async function downscale(file: File, maxSide = 640, quality = 0.72): Promise<string> {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas not supported");
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  return canvas.toDataURL("image/jpeg", quality);
}

/** One upload area per part of the property: kitchen, bathroom, bedrooms… */
export function PhotoUploadByArea({
  photos,
  onChange,
}: {
  photos: PropertyPhoto[];
  onChange: (photos: PropertyPhoto[]) => void;
}) {
  const covered = new Set(photos.map((p) => p.area)).size;
  return (
    <div>
      <p className="mb-3 text-xs text-subtle">
        {photos.length} of {LIMITS.maxPhotos} photographs · {covered} of {PHOTO_AREAS.length} areas covered · JPEG,
        PNG or WebP up to 15 MB, up to {LIMITS.maxPhotosPerArea} per area
      </p>
      <div className="grid gap-3 md:grid-cols-2">
        {PHOTO_AREAS.map((area) => (
          <AreaUpload key={area} area={area} photos={photos} onChange={onChange} />
        ))}
      </div>
    </div>
  );
}

function AreaUpload({
  area,
  photos,
  onChange,
}: {
  area: PhotoArea;
  photos: PropertyPhoto[];
  onChange: (photos: PropertyPhoto[]) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const mine = photos.filter((p) => p.area === area);
  const room = Math.min(LIMITS.maxPhotosPerArea - mine.length, LIMITS.maxPhotos - photos.length);
  const full = room <= 0;
  const inputId = `photos-${area}`;

  async function addFiles(list: FileList | null) {
    if (!list?.length) return;
    setError(null);
    const files = Array.from(list);
    const valid = files.filter((f) => ACCEPTED.includes(f.type) && f.size <= MAX_FILE_BYTES);
    const accepted = valid.slice(0, Math.max(0, room));
    if (valid.length < files.length) setError("Only JPEG, PNG or WebP images up to 15 MB are accepted.");
    else if (accepted.length < valid.length)
      setError(
        mine.length + valid.length > LIMITS.maxPhotosPerArea
          ? `Up to ${LIMITS.maxPhotosPerArea} photographs per area.`
          : `Up to ${LIMITS.maxPhotos} photographs in total.`,
      );
    if (!accepted.length) return;

    setBusy(true);
    try {
      const added = await Promise.all(
        accepted.map(async (f) => ({
          id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
          name: f.name,
          area,
          dataUrl: await downscale(f),
        })),
      );
      onChange([...photos, ...added]);
    } catch {
      setError("One of the images could not be read.");
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  function onDrop(e: DragEvent) {
    e.preventDefault();
    setDragging(false);
    if (!full) void addFiles(e.dataTransfer.files);
  }

  return (
    <div
      onDragOver={(e) => {
        e.preventDefault();
        if (!full) setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
      className={cn(
        "rounded-xl border p-3 transition-colors",
        dragging ? "border-brand-500 bg-brand-50" : "border-line bg-canvas/50",
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium">
            {PHOTO_AREA_LABELS[area]}
            {mine.length > 0 && <span className="tabular ml-1.5 text-xs font-normal text-muted">({mine.length})</span>}
          </p>
          <p className="text-xs text-subtle">{AREA_HINTS[area]}</p>
        </div>
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          disabled={full || busy}
          className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-lg border border-line-strong bg-surface px-2.5 text-xs font-medium text-text hover:bg-canvas disabled:opacity-50"
        >
          <IconUpload width={14} height={14} /> {busy ? "Processing…" : "Add"}
        </button>
        <input
          ref={inputRef}
          id={inputId}
          type="file"
          accept={ACCEPTED.join(",")}
          multiple
          className="sr-only"
          onChange={(e) => void addFiles(e.target.files)}
          aria-label={`Upload ${PHOTO_AREA_LABELS[area].toLowerCase()} photographs`}
        />
      </div>
      {error && <p className="mt-2 text-xs text-negative">{error}</p>}
      {mine.length > 0 && (
        <ul className="mt-3 grid grid-cols-4 gap-2">
          {mine.map((p) => (
            <li key={p.id} className="relative aspect-square overflow-hidden rounded-md bg-canvas ring-1 ring-line">
              {/* eslint-disable-next-line @next/next/no-img-element -- local data URL preview */}
              <img src={p.dataUrl} alt={p.name} className="h-full w-full object-cover" />
              <button
                type="button"
                onClick={() => onChange(photos.filter((x) => x.id !== p.id))}
                className="absolute top-1 right-1 flex h-5 w-5 items-center justify-center rounded-full bg-ink-900/75 text-white hover:bg-ink-900"
                aria-label={`Remove ${p.name}`}
              >
                <IconX width={12} height={12} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
