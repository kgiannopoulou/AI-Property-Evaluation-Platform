"use client";

import { API_URL, getJson, postJson, request } from "./api";
import type { PlanId } from "./plans";
import { sessionStore, signOut, type Session } from "./session";

/** Backend accounts and billing. Only used when NEXT_PUBLIC_API_URL is set. */

type SessionResponse = {
  token: string;
  expiresAt: string;
  user: { id: string; email: string; fullName: string; role: Session["role"] };
};

export type SubscriptionView = {
  plan: PlanId;
  planName: string;
  status: string;
  paymentStatus: string;
  currentPeriodEnd: string | null;
  cancelAtPeriodEnd: boolean;
  teamMember: boolean;
  canManageBilling: boolean;
  usage: { used: number; limit: number; remaining: number; resetsOn: string };
  features: string[];
  maxInspectionPhotos: number;
  maxCompare: number;
  billingMode: "stripe" | "demo" | "disabled";
};

async function startSession(r: SessionResponse): Promise<Session> {
  const base: Session = {
    name: r.user.fullName,
    email: r.user.email,
    role: r.user.role,
    plan: "free",
    joinedAt: new Date().toISOString(),
    token: r.token,
    tokenExpiresAt: r.expiresAt,
  };
  sessionStore.set(base);
  await refreshPlan();
  return sessionStore.get() ?? base;
}

export async function register(name: string, email: string, password: string): Promise<Session> {
  return startSession(await postJson<SessionResponse>("/api/v1/auth/register", { fullName: name, email, password }, "Registration"));
}

export async function login(email: string, password: string): Promise<Session> {
  return startSession(await postJson<SessionResponse>("/api/v1/auth/login", { email, password }, "Sign-in"));
}

export async function logout(): Promise<void> {
  if (API_URL && sessionStore.get()?.token) {
    await request("POST", "/api/v1/auth/logout").catch(() => undefined);
  }
  signOut();
}

export async function fetchSubscription(): Promise<SubscriptionView> {
  return getJson<SubscriptionView>("/api/v1/subscription", "Loading your plan");
}

/** Copies the backend's plan into the session so the menus show it. */
export async function refreshPlan(): Promise<SubscriptionView | null> {
  if (!API_URL || !sessionStore.get()?.token) return null;
  const view = await fetchSubscription();
  const s = sessionStore.get();
  if (s) sessionStore.set({ ...s, plan: view.plan });
  return view;
}

/** Stripe Checkout URL, or the success page in demo mode. */
export async function checkout(plan: PlanId): Promise<{ url: string; mode: string }> {
  return postJson("/api/v1/billing/checkout", { plan }, "Checkout");
}

export async function billingPortal(): Promise<{ url: string }> {
  return postJson("/api/v1/billing/portal", {}, "Opening the billing portal");
}

export async function cancelDemoPlan(): Promise<SubscriptionView> {
  return postJson("/api/v1/billing/cancel", {}, "Cancelling");
}

export async function businessEnquiry(e: {
  company: string;
  contactEmail: string;
  seats?: number;
  monthlyAnalyses?: number;
  message?: string;
}): Promise<void> {
  await postJson("/api/v1/billing/business-enquiry", e, "Sending the enquiry");
}

export type ApiKeyView = { id: string; name: string; prefix: string; createdAt: string; lastUsedAt: string | null; revokedAt: string | null };

export async function listApiKeys(): Promise<ApiKeyView[]> {
  return getJson("/api/v1/api-keys", "Loading API keys");
}

export async function createApiKey(name: string): Promise<{ key: ApiKeyView; secret: string; note: string }> {
  return postJson("/api/v1/api-keys", { name }, "Creating the key");
}

export async function revokeApiKey(id: string): Promise<void> {
  await request("DELETE", `/api/v1/api-keys/${encodeURIComponent(id)}`, { what: "Revoking the key" });
}
