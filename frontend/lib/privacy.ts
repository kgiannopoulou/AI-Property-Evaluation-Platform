"use client";

import { API_URL, getJson, postJson, request } from "./api";
import { signOut } from "./session";

/**
 * GDPR controls backed by the Spring Boot privacy API: consents, a copy of the user's data,
 * account activity and account deletion. Only used when NEXT_PUBLIC_API_URL is set; the
 * browser-only demo keeps everything in localStorage, which "Reset" clears.
 */

export type ConsentPurpose = "terms" | "privacy-notice" | "ai-photo-processing" | "marketing-email";

export type ConsentView = {
  purpose: ConsentPurpose;
  granted: boolean;
  required: boolean;
  policyVersion: string | null;
  source: string | null;
  recordedAt: string | null;
};

export type Consents = { policyVersion: string; consents: ConsentView[] };

export const CONSENT_LABELS: Record<ConsentPurpose, { label: string; hint: string }> = {
  terms: { label: "Terms of use", hint: "Needed for an account. Delete the account to end it." },
  "privacy-notice": { label: "Privacy notice", hint: "Needed for an account. Delete the account to end it." },
  "ai-photo-processing": {
    label: "AI photo processing",
    hint: "Photos and floor plans you upload are sent to our AI provider (Anthropic) for analysis. Needed for the AI Home Inspector and Floor Plan Reader.",
  },
  "marketing-email": { label: "Product news by email", hint: "Occasional emails about new features. Off unless you opt in." },
};

export type ActivityItem = { at: string; action: string; outcome: "success" | "denied" | "failure"; via: string };

export const ACTIVITY_LABELS: Record<string, string> = {
  "auth.register": "Account created",
  "auth.login": "Signed in",
  "auth.logout": "Signed out",
  "consent.grant": "Consent given",
  "consent.withdraw": "Consent withdrawn",
  "privacy.export": "Data exported",
  "privacy.erasure": "Account deletion",
  "file.read": "Uploaded file opened",
  "file.delete": "Uploaded file deleted",
  "apikey.create": "API key created",
  "apikey.revoke": "API key revoked",
  "billing.checkout": "Checkout started",
  "billing.portal": "Billing portal opened",
  "billing.cancel": "Plan cancelled",
  "access.denied": "Access refused",
};

export async function fetchConsents(): Promise<Consents> {
  return getJson<Consents>("/api/v1/privacy/consents", "Loading your consents");
}

export async function setConsent(
  purpose: ConsentPurpose,
  granted: boolean,
  source: "settings" | "prompt" = "settings",
): Promise<Consents> {
  return postJson<Consents>("/api/v1/privacy/consents", { purpose, granted, source }, "Saving your choice");
}

export async function fetchActivity(): Promise<ActivityItem[]> {
  return getJson<ActivityItem[]>("/api/v1/privacy/activity", "Loading account activity");
}

/** Downloads the JSON export as a file. */
export async function downloadMyData(): Promise<void> {
  const res = await request("GET", "/api/v1/privacy/export", { what: "The export" });
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "my-data.json";
  a.click();
  URL.revokeObjectURL(url);
}

/** Deletes the account on the backend, then this browser's session. */
export async function deleteAccount(password: string): Promise<string> {
  const r = await postJson<{ filesDeleted: number; note: string }>(
    "/api/v1/privacy/erasure",
    { password },
    "Deleting the account",
  );
  signOut();
  return r.note;
}

export const privacyApiAvailable = () => !!API_URL;
