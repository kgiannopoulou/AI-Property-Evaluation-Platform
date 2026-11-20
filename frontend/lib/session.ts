"use client";

import { createLocalStore, useLocalStore } from "./local-store";

/**
 * Prototype-only session. There is no password check and nothing leaves the
 * browser; real authentication arrives with the Spring Boot `users` module.
 */
export type PlanId = "free" | "premium" | "professional" | "business";

export type Session = {
  name: string;
  email: string;
  role: "buyer" | "investor" | "agent" | "valuer";
  plan: PlanId;
  joinedAt: string;
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

export const PLAN_LIMITS: Record<PlanId, { label: string; analysesPerMonth: number | null }> = {
  free: { label: "Free", analysesPerMonth: 3 },
  premium: { label: "Premium", analysesPerMonth: 25 },
  professional: { label: "Professional", analysesPerMonth: 150 },
  business: { label: "Business", analysesPerMonth: null },
};
