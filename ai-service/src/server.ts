import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { pathToFileURL } from "node:url";
import { DEFECT_CATEGORIES } from "./defect-detection/index.ts";
import { EXPECTED_EQUIPMENT } from "./equipment-detection/index.ts";
import { validatePhotos } from "./image-analysis/index.ts";

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

export function createApp() {
  return createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    try {
      if (req.method === "GET" && url.pathname === "/health") {
        return send(res, 200, { status: "UP", service: "property-ai-service" });
      }
      if (req.method === "GET" && url.pathname === "/v1/capabilities") {
        return send(res, 200, { defects: DEFECT_CATEGORIES, equipment: EXPECTED_EQUIPMENT, visionModel: null });
      }
      if (req.method === "POST" && url.pathname === "/v1/inspections") {
        const body = (await readJson(req)) as { propertyId?: unknown; photos?: unknown } | null;
        if (!body || typeof body.propertyId !== "string" || !validatePhotos(body.photos)) {
          return send(res, 400, { error: "propertyId and 1–20 photos with a storageKey are required" });
        }
        // Vision model integration lands in Phase 5. Being explicit beats returning fake findings.
        return send(res, 501, { error: "Visual inspection is not implemented yet" });
      }
      return send(res, 404, { error: "Not found" });
    } catch (err) {
      if (err instanceof Error && err.message === "too-large") return send(res, 413, { error: "Body too large" });
      if (err instanceof SyntaxError) return send(res, 400, { error: "Invalid JSON" });
      return send(res, 500, { error: "Internal error" });
    }
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const port = Number(process.env.PORT ?? 8090);
  createApp().listen(port, () => console.log(`ai-service listening on :${port}`));
}
