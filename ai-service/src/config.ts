import { LocalPhotoStore, SupabasePhotoStore, type PhotoStore } from "./storage/index.ts";

/** Settings from environment variables. Secrets are read here and nowhere else. */
export type Config = {
  port: number;
  /** Shared secret the backend sends as "Authorization: Bearer …". Required unless NODE_ENV=development/test. */
  serviceToken: string | null;
  model: string;
  includeIllustrativeCosts: boolean;
  store: PhotoStore;
  storeName: "local" | "supabase";
};

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const storeName = env.PHOTO_STORAGE === "supabase" ? "supabase" : "local";
  let store: PhotoStore;
  if (storeName === "supabase") {
    if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY)
      throw new Error("PHOTO_STORAGE=supabase needs SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY");
    store = new SupabasePhotoStore(env.SUPABASE_URL, env.SUPABASE_SERVICE_ROLE_KEY, env.SUPABASE_BUCKET ?? "property-photos");
  } else {
    store = new LocalPhotoStore(env.PHOTO_DIR ?? "../.data/photos");
  }

  const serviceToken = env.AI_SERVICE_TOKEN?.trim() || null;
  const devLike = env.NODE_ENV === "development" || env.NODE_ENV === "test" || env.NODE_ENV === undefined;
  if (!serviceToken && !devLike) throw new Error("AI_SERVICE_TOKEN must be set outside development");

  return {
    port: Number(env.PORT ?? 8090),
    serviceToken,
    model: env.AI_VISION_MODEL?.trim() || "claude-opus-5-5",
    includeIllustrativeCosts: env.INCLUDE_ILLUSTRATIVE_COSTS === "true",
    store,
    storeName,
  };
}
