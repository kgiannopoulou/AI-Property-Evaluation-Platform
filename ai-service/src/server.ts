import { timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { pathToFileURL } from "node:url";
import { loadConfig } from "./config.ts";
import { CATEGORY_LABELS, FINDING_CATEGORIES, POTENTIAL_CONCERN_ONLY } from "./defect-detection/index.ts";
import { CHECKLIST_ITEMS, PHOTO_AREAS } from "./equipment-detection/index.ts";
import { FloorPlanImageError, readFloorPlan } from "./floor-plan/index.ts";
import { ClaudeFloorPlanModel, type FloorPlanModel } from "./floor-plan/model.ts";
import { LIMITS, validatePhotos } from "./image-analysis/index.ts";
import { NoUsablePhotosError, parseContext, runInspection, type InspectionDeps } from "./inspection.ts";
import { isSafeStorageKey } from "./storage/index.ts";
import { ClaudeVisionModel, VisionError } from "./vision/claude.ts";

const MAX_BODY_BYTES = 64 * 1024;

function send(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

async function readJson(req: IncomingMessage): Promise<unknown> {
  let size = 0;
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > MAX_BODY_BYTES) throw new Error("too-large");
    chunks.push(chunk as Buffer);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "null");
}

function authorised(req: IncomingMessage, token: string | null): boolean {
  if (!token) return true;
  const header = req.headers.authorization ?? "";
  const given = Buffer.from(header.startsWith("Bearer ") ? header.slice(7) : "");
  const expected = Buffer.from(token);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

export type AppOptions = InspectionDeps & { serviceToken: string | null; floorPlans?: FloorPlanModel };

export function createApp(options: AppOptions) {
  return createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    try {
      if (req.method === "GET" && url.pathname === "/health") {
        return send(res, 200, { status: "UP", service: "property-ai-service" });
      }
      if (url.pathname.startsWith("/v1/") && !authorised(req, options.serviceToken)) {
        return send(res, 401, { error: "Unauthorised" });
      }
      if (req.method === "GET" && url.pathname === "/v1/capabilities") {
        return send(res, 200, {
          visionModel: options.vision.id,
          categories: FINDING_CATEGORIES.map((c) => ({
            id: c,
            label: CATEGORY_LABELS[c],
            potentialConcernOnly: POTENTIAL_CONCERN_ONLY.has(c),
          })),
          photoAreas: PHOTO_AREAS,
          checklistItems: CHECKLIST_ITEMS,
          maxPhotos: LIMITS.maxPhotos,
          maxImageBytes: LIMITS.maxImageBytes,
          illustrativeCosts: options.includeIllustrativeCosts,
        });
      }
      if (req.method === "POST" && url.pathname === "/v1/inspections") {
        const body = (await readJson(req)) as { propertyId?: unknown; photos?: unknown; context?: unknown } | null;
        if (!body || typeof body.propertyId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(body.propertyId)) {
          return send(res, 400, { error: "propertyId is required" });
        }
        if (!validatePhotos(body.photos)) {
          return send(res, 400, {
            error: `photos must be 1-${LIMITS.maxPhotos} items with a unique photoId, a storageKey and an area (${PHOTO_AREAS.join(", ")})`,
          });
        }
        const report = await runInspection(
          { propertyId: body.propertyId, photos: body.photos, context: parseContext(body.context) },
          options,
        );
        return send(res, 200, report);
      }
      if (req.method === "POST" && url.pathname === "/v1/floor-plans") {
        const body = (await readJson(req)) as { planId?: unknown; storageKey?: unknown } | null;
        if (!body || typeof body.planId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(body.planId))
          return send(res, 400, { error: "planId is required" });
        if (typeof body.storageKey !== "string" || !isSafeStorageKey(body.storageKey))
          return send(res, 400, { error: "storageKey is invalid" });
        if (!options.floorPlans) return send(res, 503, { error: "The floor plan reader is not configured.", retryable: false });
        const reading = await readFloorPlan(
          { planId: body.planId, storageKey: body.storageKey },
          { store: options.store, floorPlans: options.floorPlans },
        );
        return send(res, 200, reading);
      }
      return send(res, 404, { error: "Not found" });
    } catch (err) {
      if (err instanceof Error && err.message === "too-large") return send(res, 413, { error: "Body too large" });
      if (err instanceof SyntaxError) return send(res, 400, { error: "Invalid JSON" });
      if (err instanceof FloorPlanImageError) return send(res, 422, { error: err.message });
      if (err instanceof NoUsablePhotosError) return send(res, 422, { error: err.message, skipped: err.skipped });
      if (err instanceof VisionError) return send(res, err.retryable ? 503 : 502, { error: err.message, retryable: err.retryable });
      console.error(err);
      return send(res, 500, { error: "Internal error" });
    }
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const config = loadConfig();
  createApp({
    store: config.store,
    vision: new ClaudeVisionModel(config.model),
    floorPlans: new ClaudeFloorPlanModel(config.model),
    includeIllustrativeCosts: config.includeIllustrativeCosts,
    serviceToken: config.serviceToken,
  }).listen(config.port, () =>
    console.log(
      `ai-service listening on :${config.port} (model ${config.model}, ${config.storeName} photo storage${config.serviceToken ? "" : ", no service token: development only"})`,
    ),
  );
}
