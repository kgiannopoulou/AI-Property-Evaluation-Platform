import { LIMITS, sniffMediaType } from "../image-analysis/index.ts";
import { PhotoNotFoundError, type PhotoStore } from "../storage/index.ts";
import type { FloorPlanModel } from "./model.ts";
import type { FloorPlanResult, RoomType } from "./schema.ts";

export type FloorPlanRequest = { planId: string; storageKey: string };

export type FloorPlanRoom = {
  label: string;
  roomType: RoomType;
  areaSqm: number | null;
  /** printed: area printed on the plan; computed: width × length, both printed; none: not readable. */
  areaSource: "printed" | "computed" | "none";
  widthM: number | null;
  lengthM: number | null;
  printedText: string;
  confidence: number;
  warnings: string[];
};

export type FloorPlanReading = {
  planId: string;
  model: string;
  servedBy: string;
  isFloorPlan: boolean;
  legibility: FloorPlanResult["legibility"];
  units: FloorPlanResult["units"];
  scalePrinted: string | null;
  rooms: FloorPlanRoom[];
  totalAreaPrinted: number | null;
  totalAreaText: string;
  notes: string[];
  disclaimer: string;
};

export class FloorPlanImageError extends Error {}

const round2 = (n: number) => Math.round(n * 100) / 100;
const plausibleLength = (n: number | null) => (n !== null && Number.isFinite(n) && n > 0.3 && n <= 60 ? n : null);
const plausibleArea = (n: number | null) => (n !== null && Number.isFinite(n) && n > 0.5 && n <= 1500 ? n : null);

/** Model output to a checked reading: implausible numbers dropped, areas computed only from printed sides. */
export function postProcess(planId: string, model: string, servedBy: string, r: FloorPlanResult): FloorPlanReading {
  const rooms: FloorPlanRoom[] = r.is_floor_plan
    ? r.rooms.slice(0, 60).map((room) => {
        const warnings: string[] = [];
        const width = plausibleLength(room.width_m);
        const length = plausibleLength(room.length_m);
        if (room.width_m !== null && width === null) warnings.push(`Dimension ${room.width_m} m is implausible and was ignored.`);
        if (room.length_m !== null && length === null) warnings.push(`Dimension ${room.length_m} m is implausible and was ignored.`);
        let area = plausibleArea(room.area_sqm);
        if (room.area_sqm !== null && area === null) warnings.push(`Area ${room.area_sqm} m² is implausible and was ignored.`);
        let source: FloorPlanRoom["areaSource"] = area !== null ? "printed" : "none";
        if (area === null && width !== null && length !== null) {
          area = round2(width * length);
          source = "computed";
        } else if (area !== null && width !== null && length !== null) {
          const product = width * length;
          if (Math.abs(product - area) / area > 0.15)
            warnings.push(
              `Printed area ${area} m² differs from ${width} × ${length} m = ${round2(product)} m²; the room may not be rectangular.`,
            );
        }
        const confidence = Math.min(1, Math.max(0, room.confidence));
        if (confidence < 0.5) warnings.push("Low confidence: check this reading against the plan.");
        return {
          label: room.label.slice(0, 80),
          roomType: room.room_type,
          areaSqm: area,
          areaSource: source,
          widthM: width,
          lengthM: length,
          printedText: room.printed_text.slice(0, 120),
          confidence,
          warnings,
        };
      })
    : [];
  return {
    planId,
    model,
    servedBy,
    isFloorPlan: r.is_floor_plan,
    legibility: r.legibility,
    units: r.units,
    scalePrinted: r.scale_printed,
    rooms,
    totalAreaPrinted: plausibleArea(r.total_area_sqm),
    totalAreaText: r.total_area_text.slice(0, 120),
    notes: r.notes.slice(0, 20),
    disclaimer:
      "Transcribed by an AI model from the measurements printed on the plan; nothing was measured from the drawing. Check figures against the official plan and an on-site measurement.",
  };
}

export async function readFloorPlan(
  req: FloorPlanRequest,
  deps: { store: PhotoStore; floorPlans: FloorPlanModel },
): Promise<FloorPlanReading> {
  let data: Buffer;
  try {
    data = await deps.store.read(req.storageKey);
  } catch (err) {
    if (err instanceof PhotoNotFoundError) throw new FloorPlanImageError("The floor plan image was not found.");
    throw err;
  }
  if (data.length > LIMITS.maxImageBytes) throw new FloorPlanImageError("The floor plan image is too large.");
  const mediaType = sniffMediaType(data);
  if (!mediaType) throw new FloorPlanImageError("The floor plan must be a JPEG, PNG, WebP or GIF image.");
  const { result, servedBy } = await deps.floorPlans.read({ mediaType, data });
  return postProcess(req.planId, deps.floorPlans.id, servedBy, result);
}
