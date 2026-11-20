"use client";

import { useRef, useState, type DragEvent } from "react";
import { IconUpload, IconX } from "@/components/icons";
import { cn } from "@/lib/format";
import type { PropertyPhoto } from "./types";

export const MAX_PHOTOS = 8;
const MAX_FILE_BYTES = 15 * 1024 * 1024;
const ACCEPTED = ["image/jpeg", "image/png", "image/webp"];

/**
 * Downscale an image in the browser to a ~640px JPEG. Phase 3 keeps photos in
 * localStorage, so originals are never stored; Phase 4 uploads originals to
 * object storage (Supabase Storage) through the backend instead.
 */
async function downscale(
  file: File,
  maxSide = 640,
  quality = 0.72,
): Promise<string> {
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

export function PhotoUpload({
  photos,
  onChange,
}: {
  photos: PropertyPhoto[];
  onChange: (photos: PropertyPhoto[]) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function addFiles(list: FileList | null) {
    if (!list?.length) return;
    setError(null);
    const files = Array.from(list);
    const rejected = files.filter(
      (f) => !ACCEPTED.includes(f.type) || f.size > MAX_FILE_BYTES,
    );
    const accepted = files
      .filter((f) => ACCEPTED.includes(f.type) && f.size <= MAX_FILE_BYTES)
      .slice(0, MAX_PHOTOS - photos.length);
    if (rejected.length)
      setError("Only JPEG, PNG or WebP images up to 15 MB are accepted.");
    else if (files.length > accepted.length)
      setError(`You can add up to ${MAX_PHOTOS} photographs.`);
    if (!accepted.length) return;

    setBusy(true);
    try {
      const added = await Promise.all(
        accepted.map(async (f) => ({
          id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
          name: f.name,
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
    void addFiles(e.dataTransfer.files);
  }

  const full = photos.length >= MAX_PHOTOS;

  return (
    <div>
      <div
        onDragOver={(e) => {
          e.preventDefault();
          if (!full) setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={cn(
          "flex flex-col items-center justify-center rounded-xl border-2 border-dashed px-6 py-8 text-center transition-colors",
          dragging
            ? "border-brand-500 bg-brand-50"
            : "border-line-strong bg-canvas/60",
          full && "opacity-60",
        )}
      >
        <span className="flex h-10 w-10 items-center justify-center rounded-full bg-surface text-brand-600 ring-1 ring-line">
          <IconUpload />
        </span>
        <p className="mt-3 text-sm font-medium">
          {busy ? "Processing photographs…" : "Drag photographs here, or"}{" "}
          {!busy && (
            <button
              type="button"
              className="font-semibold text-brand-700 underline-offset-2 hover:underline disabled:no-underline"
              onClick={() => inputRef.current?.click()}
              disabled={full}
            >
              browse
            </button>
          )}
        </p>
        <p className="mt-1 text-xs text-subtle">
          JPEG, PNG or WebP · up to {MAX_PHOTOS} photos · include every room,
          the façade and any visible damage
        </p>
        <input
          ref={inputRef}
          type="file"
          accept={ACCEPTED.join(",")}
          multiple
          className="sr-only"
          onChange={(e) => void addFiles(e.target.files)}
          aria-label="Upload property photographs"
        />
      </div>
      {error && <p className="mt-2 text-xs text-negative">{error}</p>}
      {photos.length > 0 && (
        <ul className="mt-4 grid grid-cols-3 gap-3 sm:grid-cols-4">
          {photos.map((p) => (
            <li
              key={p.id}
              className="group relative aspect-[4/3] overflow-hidden rounded-lg bg-canvas ring-1 ring-line"
            >
              {/* eslint-disable-next-line @next/next/no-img-element -- local data URL preview */}
              <img
                src={p.dataUrl}
                alt={p.name}
                className="h-full w-full object-cover"
              />
              <button
                type="button"
                onClick={() => onChange(photos.filter((x) => x.id !== p.id))}
                className="absolute top-1.5 right-1.5 flex h-6 w-6 items-center justify-center rounded-full bg-ink-900/75 text-white opacity-90 hover:bg-ink-900"
                aria-label={`Remove ${p.name}`}
              >
                <IconX width={14} height={14} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
