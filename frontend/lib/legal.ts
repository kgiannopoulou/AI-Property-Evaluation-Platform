/**
 * Facts the privacy notice and terms state. The retention periods mirror the backend defaults in
 * backend/src/main/resources/application.yml (app.retention.*); lib/legal.test.ts keeps them in step.
 */

export const POLICY_VERSION = "2026-11-25";

export type RetentionRow = { data: string; kept: string; key?: string; value?: number };

export const RETENTION: RetentionRow[] = [
  { data: "Account (name, email, role, password hash)", kept: "Until you delete the account" },
  { data: "Sign-in sessions", kept: "30 days, or until you sign out" },
  {
    data: "Uploaded photos and floor plans",
    kept: "180 days after upload, unless you delete them or the account earlier",
    key: "uploaded-file-days",
    value: 180,
  },
  { data: "Usage records (which property was analysed, when)", kept: "24 months", key: "usage-months", value: 24 },
  { data: "Security and privacy audit log", kept: "365 days, without email or IP address", key: "audit-days", value: 365 },
  { data: "Payment webhook ids (to process each payment event once)", kept: "90 days", key: "billing-event-days", value: 90 },
  { data: "Business plan enquiries", kept: "24 months", key: "enquiry-months", value: 24 },
  { data: "Revoked API keys", kept: "90 days after revocation", key: "revoked-key-days", value: 90 },
  { data: "Consent records", kept: "As long as the account exists (proof of consent)" },
  { data: "Database backups", kept: "14 days (rolling); erased data leaves the backups within that time" },
];

export type Processor = { name: string; purpose: string; data: string; when: string };

export const PROCESSORS: Processor[] = [
  {
    name: "Anthropic (Claude API)",
    purpose: "AI analysis of property photos and floor plans",
    data: "The images you upload and the property's type and checklist; no name or email",
    when: "Only after you allow AI photo processing",
  },
  {
    name: "Stripe",
    purpose: "Subscription payments, invoices, billing portal",
    data: "Email, plan, payment details (entered on Stripe's pages, never sent to us)",
    when: "When you buy a paid plan",
  },
  {
    name: "Supabase Storage (if configured)",
    purpose: "Private storage of uploaded photos",
    data: "The uploaded images",
    when: "When you upload photos",
  },
  {
    name: "Hosting and database provider",
    purpose: "Running the website, API and database",
    data: "All data above, encrypted in transit",
    when: "Always",
  },
];
