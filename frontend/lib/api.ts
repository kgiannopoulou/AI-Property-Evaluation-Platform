/**
 * Backend (Spring Boot) client. NEXT_PUBLIC_API_URL is the backend's public base URL, e.g.
 * http://localhost:8080; when it is unset the backend-only panels explain how to connect it.
 *
 * Every call sends the signed-in user's bearer token. Plans and usage limits are enforced by the
 * backend; its problem responses (401, 403 upgrade-required, 429 quota-exceeded) become ApiErrors
 * the pages can show with an upgrade link.
 */
import { currentToken, sessionStore } from "./session";

export const API_URL = process.env.NEXT_PUBLIC_API_URL?.replace(/\/$/, "") ?? "";

export type ApiErrorCode = "unauthenticated" | "upgrade-required" | "quota-exceeded" | "other";

export class ApiError extends Error {
  readonly code: ApiErrorCode;
  readonly status: number;
  readonly requiredPlan?: string;

  constructor(message: string, code: ApiErrorCode = "other", status = 0, requiredPlan?: string) {
    super(message);
    this.code = code;
    this.status = status;
    this.requiredPlan = requiredPlan;
  }
}

/** Authorization header, plus the property the call analyses (re-running a property is not counted again). */
export function authHeaders(propertyRef?: string): Record<string, string> {
  const h: Record<string, string> = {};
  const token = currentToken();
  if (token) h.Authorization = `Bearer ${token}`;
  if (propertyRef) h["X-Property-Ref"] = propertyRef.replace(/[^A-Za-z0-9_.:-]/g, "-").slice(0, 100);
  return h;
}

export async function request(
  method: "GET" | "POST" | "DELETE",
  path: string,
  options: { body?: unknown; propertyRef?: string; what?: string } = {},
): Promise<Response> {
  if (!API_URL) throw new ApiError("The backend is not connected.");
  const headers: Record<string, string> = authHeaders(options.propertyRef);
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  let res: Response;
  try {
    res = await fetch(`${API_URL}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiError("Could not reach the backend. Is it running?");
  }
  if (!res.ok) throw await toApiError(res, options.what ?? "The request");
  return res;
}

export async function toApiError(res: Response, what: string): Promise<ApiError> {
  const problem = (await res.json().catch(() => null)) as { detail?: string; code?: string; requiredPlan?: string } | null;
  const code: ApiErrorCode =
    problem?.code === "unauthenticated" || problem?.code === "upgrade-required" || problem?.code === "quota-exceeded"
      ? problem.code
      : "other";
  if (code === "unauthenticated" && sessionStore.get()?.token) {
    // The backend no longer accepts the token: drop it so the user signs in again.
    const s = sessionStore.get();
    if (s) sessionStore.set({ ...s, token: undefined, tokenExpiresAt: undefined });
  }
  return new ApiError(problem?.detail ?? `${what} failed (${res.status}).`, code, res.status, problem?.requiredPlan);
}

/** POST JSON and parse the JSON answer. */
export async function postJson<T>(path: string, body: unknown, what = "The request", propertyRef?: string): Promise<T> {
  return (await (await request("POST", path, { body, what, propertyRef })).json()) as T;
}

export async function getJson<T>(path: string, what = "The request"): Promise<T> {
  return (await (await request("GET", path, { what })).json()) as T;
}

export async function postRaw(path: string, body: unknown, what = "The request", propertyRef?: string): Promise<Response> {
  return request("POST", path, { body, what, propertyRef });
}
