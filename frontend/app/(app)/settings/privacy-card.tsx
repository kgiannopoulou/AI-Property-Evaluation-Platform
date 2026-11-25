"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { Button, Card, CardHeader, Field } from "@/components/ui";
import { ApiError } from "@/lib/api";
import { formatDate } from "@/lib/format";
import {
  ACTIVITY_LABELS,
  CONSENT_LABELS,
  deleteAccount,
  downloadMyData,
  fetchActivity,
  fetchConsents,
  setConsent,
  type ActivityItem,
  type Consents,
} from "@/lib/privacy";

const message = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback);

/** GDPR controls: consents, data export, account activity and account deletion (backend accounts). */
export function PrivacyCard() {
  const [consents, setConsents] = useState<Consents | null>(null);
  const [activity, setActivity] = useState<ActivityItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    Promise.all([fetchConsents(), fetchActivity()])
      .then(([c, a]) => {
        if (!live) return;
        setConsents(c);
        setActivity(a);
      })
      .catch((e) => live && setError(message(e, "Could not load your privacy settings.")));
    return () => {
      live = false;
    };
  }, []);

  async function toggle(purpose: Consents["consents"][number]["purpose"], granted: boolean) {
    setBusy(purpose);
    setError(null);
    const before = consents;
    // Show the choice at once; the server's answer (or the old state on failure) replaces it.
    setConsents(
      (c) => c && { ...c, consents: c.consents.map((x) => (x.purpose === purpose ? { ...x, granted } : x)) },
    );
    try {
      setConsents(await setConsent(purpose, granted));
      setActivity(await fetchActivity());
    } catch (e) {
      setConsents(before);
      setError(message(e, "Could not save your choice."));
    } finally {
      setBusy(null);
    }
  }

  async function exportData() {
    setBusy("export");
    setError(null);
    try {
      await downloadMyData();
      setActivity(await fetchActivity());
    } catch (e) {
      setError(message(e, "The export failed."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card id="privacy" className="scroll-mt-20">
      <CardHeader
        title="Privacy"
        description={
          <>
            Your consents, a copy of your data and account deletion. See the{" "}
            <Link href="/privacy" className="font-medium text-brand-700 hover:underline">
              privacy notice
            </Link>
            .
          </>
        }
      />
      <div className="space-y-6 p-5">
        {error && (
          <p role="alert" className="rounded-lg border border-negative/20 bg-negative-soft px-3 py-2 text-sm text-negative">
            {error}
          </p>
        )}

        <div>
          <h3 className="text-sm font-semibold">Consents</h3>
          {!consents ? (
            <div className="mt-2 h-24 animate-pulse rounded-lg bg-line/60" aria-busy="true" />
          ) : (
            <ul className="mt-2 divide-y divide-line rounded-lg ring-1 ring-line">
              {consents.consents.map((c) => (
                <li key={c.purpose} className="flex items-start justify-between gap-4 px-4 py-3">
                  <div className="min-w-0">
                    <p className="text-sm font-medium">{CONSENT_LABELS[c.purpose].label}</p>
                    <p className="text-xs text-muted">{CONSENT_LABELS[c.purpose].hint}</p>
                    {c.recordedAt && (
                      <p className="mt-0.5 text-xs text-subtle">
                        {c.granted ? "Given" : "Not given"} {formatDate(c.recordedAt)} · version {c.policyVersion}
                      </p>
                    )}
                  </div>
                  <input
                    type="checkbox"
                    className="mt-1 h-4 w-4 shrink-0 accent-brand-600"
                    checked={c.granted}
                    disabled={c.required || busy === c.purpose}
                    onChange={(e) => toggle(c.purpose, e.target.checked)}
                    aria-label={CONSENT_LABELS[c.purpose].label}
                  />
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h3 className="text-sm font-semibold">Download my data</h3>
            <p className="text-xs text-muted">
              Account, plan, usage, consents, uploaded files and activity, as JSON (GDPR art. 15 and 20).
            </p>
          </div>
          <Button variant="secondary" onClick={exportData} disabled={busy === "export"}>
            {busy === "export" ? "Preparing…" : "Download my data"}
          </Button>
        </div>

        <div>
          <h3 className="text-sm font-semibold">Recent account activity</h3>
          {activity && activity.length === 0 && <p className="mt-1 text-xs text-muted">No activity yet.</p>}
          {activity && activity.length > 0 && (
            <ul className="mt-2 max-h-56 divide-y divide-line overflow-y-auto rounded-lg text-sm ring-1 ring-line">
              {activity.slice(0, 20).map((a, i) => (
                <li key={i} className="flex items-center justify-between gap-3 px-4 py-2">
                  <span className="min-w-0 truncate">
                    {ACTIVITY_LABELS[a.action] ?? a.action}
                    {a.outcome !== "success" && <span className="text-negative"> ({a.outcome})</span>}
                    {a.via === "api-key" && <span className="text-muted"> · API key</span>}
                  </span>
                  <span className="shrink-0 text-xs text-muted">{formatDate(a.at)}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <DeleteAccount />
      </div>
    </Card>
  );
}

function DeleteAccount() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!password) return setError("Enter your password to confirm.");
    setBusy(true);
    setError(null);
    try {
      await deleteAccount(password);
      router.push("/?account=deleted");
    } catch (err) {
      setError(message(err, "The account could not be deleted."));
      setBusy(false);
    }
  }

  return (
    <div className="rounded-lg border border-negative/20 p-4">
      <h3 className="text-sm font-semibold">Delete account</h3>
      <p className="mt-1 text-xs text-muted">
        Deletes your account, sessions, API keys, team, usage, consents and uploaded photos and floor plans. It cannot be
        undone. Cancel a paid plan in the billing portal first.
      </p>
      {open ? (
        <form onSubmit={onSubmit} className="mt-3 grid gap-3 sm:grid-cols-[1fr_auto_auto] sm:items-end" noValidate>
          <Field label="Confirm with your password" htmlFor="delete-password" error={error ?? undefined}>
            <input
              id="delete-password"
              type="password"
              className="input"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-invalid={!!error}
            />
          </Field>
          <Button type="submit" variant="danger" disabled={busy}>
            {busy ? "Deleting…" : "Delete permanently"}
          </Button>
          <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
            Cancel
          </Button>
        </form>
      ) : (
        <Button variant="danger" className="mt-3" onClick={() => setOpen(true)}>
          Delete account…
        </Button>
      )}
    </div>
  );
}
