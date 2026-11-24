/**
 * Backend (Spring Boot) client. NEXT_PUBLIC_API_URL is the backend's public base URL, e.g.
 * http://localhost:8080; when it is unset the backend-only panels explain how to connect it.
 */
export const API_URL = process.env.NEXT_PUBLIC_API_URL?.replace(/\/$/, "") ?? "";

export class ApiError extends Error {}

/** POST JSON and parse the JSON answer; errors carry the backend's problem detail when it sends one. */
export async function postJson<T>(path: string, body: unknown, what = "The request"): Promise<T> {
  const res = await postRaw(path, body, what);
  return (await res.json()) as T;
}

export async function postRaw(path: string, body: unknown, what = "The request"): Promise<Response> {
  if (!API_URL) throw new ApiError("The backend is not connected.");
  let res: Response;
  try {
    res = await fetch(`${API_URL}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError("Could not reach the backend. Is it running?");
  }
  if (!res.ok) {
    const problem = (await res.json().catch(() => null)) as { detail?: string } | null;
    throw new ApiError(problem?.detail ?? `${what} failed (${res.status}).`);
  }
  return res;
}
