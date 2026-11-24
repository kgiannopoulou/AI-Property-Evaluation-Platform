"use client";

import { createLocalStore, useLocalStore } from "./local-store";
import { planInfo, type PlanId } from "./plans";

export type { PlanId } from "./plans";

/**
 * The signed-in user. With a backend (NEXT_PUBLIC_API_URL) the session holds the backend's bearer
 * token and the plan comes from the backend, which enforces it. Without a backend the website runs as
 * a browser-only demo: no password check, nothing leaves the browser.
 */
export type Session = {
  name: string;
  email: string;
  role: "buyer" | "investor" | "agent" | "valuer";
  plan: PlanId;
  joinedAt: string;
  /** Backend session token (absent in the browser-only demo). */
  token?: string;
  tokenExpiresAt?: string;
};

export type Preferences = {
  mortgageRatePct: number;
  loanToValuePct: number;
  mortgageYears: number;
  emailReports: boolean;
  priceAlerts: boolean;
  productNews: boolean;
};

export const sessionStore = createLocalStore<Session | null>("pi.session.v1", () => null);
export const preferencesStore = createLocalStore<Preferences>("pi.preferences.v1", () => DEFAULT_PREFERENCES);

export const DEFAULT_PREFERENCES: Preferences = {
  mortgageRatePct: 3.9,
  loanToValuePct: 80,
  mortgageYears: 25,
  emailReports: true,
  priceAlerts: false,
  productNews: false,
};

export const DEMO_SESSION: Session = {
  name: "Demo user",
  email: "demo@example.com",
  role: "buyer",
  plan: "free",
  joinedAt: "2026-11-01T00:00:00.000Z",
};

export function useSession(): Session | null {
  return useLocalStore(sessionStore, null);
}

export function usePreferences(): Preferences {
  return useLocalStore(preferencesStore, DEFAULT_PREFERENCES);
}

/** Browser-only demo sign-in (no backend). */
export function signIn(email: string, name?: string): Session {
  const existing = sessionStore.get();
  const session: Session = {
    name: name?.trim() || existing?.name || email.split("@")[0],
    email: email.trim().toLowerCase(),
    role: existing?.role ?? "buyer",
    plan: existing?.plan ?? "free",
    joinedAt: existing?.joinedAt ?? new Date().toISOString(),
  };
  sessionStore.set(session);
  return session;
}

export function signOut(): void {
  sessionStore.set(null);
}

/** Bearer token for backend calls, if the user signed in against the backend. */
export function currentToken(): string | null {
  const s = sessionStore.get();
  if (!s?.token) return null;
  if (s.tokenExpiresAt && new Date(s.tokenExpiresAt).getTime() < Date.now()) return null;
  return s.token;
}

export const PLAN_LIMITS: Record<PlanId, { label: string; analysesPerMonth: number | null }> = {
  free: { label: planInfo("free").name, analysesPerMonth: planInfo("free").analysesPerMonth },
  premium: { label: planInfo("premium").name, analysesPerMonth: planInfo("premium").analysesPerMonth },
  professional: { label: planInfo("professional").name, analysesPerMonth: planInfo("professional").analysesPerMonth },
  business: { label: planInfo("business").name, analysesPerMonth: planInfo("business").analysesPerMonth },
};
