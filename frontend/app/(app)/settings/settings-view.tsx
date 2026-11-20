"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button, ButtonLink, Card, CardHeader, Field, PageHeader } from "@/components/ui";
import { formatDate } from "@/lib/format";
import { useHydrated } from "@/lib/local-store";
import {
  DEFAULT_PREFERENCES,
  PLAN_LIMITS,
  preferencesStore,
  sessionStore,
  signOut,
  usePreferences,
  useSession,
  type Preferences,
  type Session,
} from "@/lib/session";
import { resetDemoData } from "@/properties/store";

const ROLES: [Session["role"], string][] = [
  ["buyer", "Home buyer"],
  ["investor", "Investor"],
  ["agent", "Estate agent"],
  ["valuer", "Valuer / surveyor"],
];

export function SettingsView() {
  const hydrated = useHydrated();
  const session = useSession();
  const prefs = usePreferences();
  if (!hydrated) return <div className="h-96 animate-pulse rounded-xl bg-line/60" aria-busy="true" />;

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title="Profile & settings" description="Your account, analysis defaults and notifications." />
      <div className="space-y-6">
        {session ? (
          <ProfileCard key={session.email} session={session} />
        ) : (
          <Card className="flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <p className="text-sm font-semibold">You are using the demo account</p>
              <p className="text-sm text-muted">
                Create an account to keep a profile. Data still stays in this browser.
              </p>
            </div>
            <ButtonLink href="/register">Create account</ButtonLink>
          </Card>
        )}
        <AssumptionsCard prefs={prefs} />
        <NotificationsCard prefs={prefs} />
        <DataCard signedIn={!!session} />
      </div>
    </div>
  );
}

function Saved({ show }: { show: boolean }) {
  return show ? (
    <span role="status" className="text-sm text-positive">
      Saved
    </span>
  ) : null;
}

function ProfileCard({ session }: { session: Session }) {
  const [name, setName] = useState(session.name);
  const [role, setRole] = useState(session.role);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (name.trim().length < 2) return setError("Enter your name.");
    setError(null);
    sessionStore.set({ ...session, name: name.trim(), role });
    setSaved(true);
  }

  return (
    <Card>
      <CardHeader
        title="Profile"
        description={`${session.email} · member since ${formatDate(session.joinedAt)}`}
        action={
          <span className="text-sm">
            {PLAN_LIMITS[session.plan].label} plan ·{" "}
            <Link href="/pricing" className="font-medium text-brand-700 hover:underline">
              Change
            </Link>
          </span>
        }
      />
      <form onSubmit={onSubmit} className="grid gap-4 p-5 sm:grid-cols-2" noValidate>
        <Field label="Full name" htmlFor="profile-name" error={error ?? undefined}>
          <input
            id="profile-name"
            className="input"
            value={name}
            aria-invalid={!!error}
            onChange={(e) => {
              setName(e.target.value);
              setSaved(false);
            }}
          />
        </Field>
        <Field label="I am a" htmlFor="profile-role">
          <select
            id="profile-role"
            className="input"
            value={role}
            onChange={(e) => {
              setRole(e.target.value as Session["role"]);
              setSaved(false);
            }}
          >
            {ROLES.map(([id, label]) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <div className="flex items-center gap-3 sm:col-span-2">
          <Button type="submit">Save profile</Button>
          <Saved show={saved} />
        </div>
      </form>
    </Card>
  );
}

function AssumptionsCard({ prefs }: { prefs: Preferences }) {
  const [rate, setRate] = useState(String(prefs.mortgageRatePct));
  const [ltv, setLtv] = useState(String(prefs.loanToValuePct));
  const [years, setYears] = useState(String(prefs.mortgageYears));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    const r = Number(rate);
    const l = Number(ltv);
    const y = Number(years);
    const next: Record<string, string> = {};
    if (!Number.isFinite(r) || r < 0 || r > 15) next.rate = "0–15%";
    if (!Number.isFinite(l) || l < 0 || l > 100) next.ltv = "0–100%";
    if (!Number.isInteger(y) || y < 1 || y > 40) next.years = "1–40 years";
    setErrors(next);
    if (Object.keys(next).length) return;
    preferencesStore.set({ ...prefs, mortgageRatePct: r, loanToValuePct: l, mortgageYears: y });
    setSaved(true);
  }

  return (
    <Card>
      <CardHeader
        title="Mortgage assumptions"
        description="Used by the financial calculator on new and re-run analyses"
      />
      <form onSubmit={onSubmit} className="grid gap-4 p-5 sm:grid-cols-3" noValidate>
        <Field label="Interest rate (%)" htmlFor="rate" error={errors.rate}>
          <input
            id="rate"
            className="input"
            inputMode="decimal"
            value={rate}
            onChange={(e) => setRate(e.target.value)}
            aria-invalid={!!errors.rate}
          />
        </Field>
        <Field label="Loan to value (%)" htmlFor="ltv" error={errors.ltv}>
          <input
            id="ltv"
            className="input"
            inputMode="numeric"
            value={ltv}
            onChange={(e) => setLtv(e.target.value)}
            aria-invalid={!!errors.ltv}
          />
        </Field>
        <Field label="Term (years)" htmlFor="years" error={errors.years}>
          <input
            id="years"
            className="input"
            inputMode="numeric"
            value={years}
            onChange={(e) => setYears(e.target.value)}
            aria-invalid={!!errors.years}
          />
        </Field>
        <div className="flex flex-wrap items-center gap-3 sm:col-span-3">
          <Button type="submit">Save assumptions</Button>
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              const d = DEFAULT_PREFERENCES;
              preferencesStore.set({
                ...prefs,
                mortgageRatePct: d.mortgageRatePct,
                loanToValuePct: d.loanToValuePct,
                mortgageYears: d.mortgageYears,
              });
              setRate(String(d.mortgageRatePct));
              setLtv(String(d.loanToValuePct));
              setYears(String(d.mortgageYears));
              setErrors({});
              setSaved(true);
            }}
          >
            Reset to defaults
          </Button>
          <Saved show={saved} />
        </div>
      </form>
    </Card>
  );
}

function NotificationsCard({ prefs }: { prefs: Preferences }) {
  const items: [keyof Preferences, string, string][] = [
    ["emailReports", "Email me a copy of each report", "Sent when email delivery is enabled"],
    ["priceAlerts", "Price change alerts", "When a saved property's asking price changes"],
    ["productNews", "Product updates", "New modules such as the AI Home Inspector"],
  ];
  return (
    <Card>
      <CardHeader title="Notifications" description="Preferences are saved; email delivery arrives with the backend" />
      <ul className="divide-y divide-line">
        {items.map(([key, label, hint]) => (
          <li key={key} className="flex items-center justify-between gap-4 px-5 py-3.5">
            <div>
              <p className="text-sm font-medium">{label}</p>
              <p className="text-xs text-muted">{hint}</p>
            </div>
            <input
              type="checkbox"
              className="h-4 w-4 accent-brand-600"
              checked={prefs[key] as boolean}
              onChange={(e) => preferencesStore.set({ ...prefs, [key]: e.target.checked })}
              aria-label={label}
            />
          </li>
        ))}
      </ul>
    </Card>
  );
}

function DataCard({ signedIn }: { signedIn: boolean }) {
  const router = useRouter();
  const [confirm, setConfirm] = useState(false);
  return (
    <Card>
      <CardHeader title="Data" description="Everything in this preview is stored in your browser only" />
      <div className="flex flex-wrap items-center gap-3 p-5">
        {confirm ? (
          <>
            <span className="text-sm text-muted">Delete your properties and restore the sample data?</span>
            <Button
              variant="danger"
              onClick={() => {
                resetDemoData();
                setConfirm(false);
              }}
            >
              Yes, reset
            </Button>
            <Button variant="ghost" onClick={() => setConfirm(false)}>
              Cancel
            </Button>
          </>
        ) : (
          <Button variant="danger" onClick={() => setConfirm(true)}>
            Reset to sample data
          </Button>
        )}
        {signedIn && (
          <Button
            variant="secondary"
            onClick={() => {
              signOut();
              router.push("/");
            }}
          >
            Sign out
          </Button>
        )}
      </div>
    </Card>
  );
}
