"use client";

import { useCallback, useEffect, useState } from "react";
import { Badge, Button, ButtonLink, Card, CardHeader } from "@/components/ui";
import {
  billingPortal,
  cancelDemoPlan,
  createApiKey,
  listApiKeys,
  refreshPlan,
  revokeApiKey,
  type ApiKeyView,
  type SubscriptionView,
} from "@/lib/account";
import { ApiError } from "@/lib/api";
import { formatDate } from "@/lib/format";

/** Plan, monthly usage and billing actions, as the backend reports them. */
export function PlanUsageCard({ justUpgraded }: { justUpgraded: boolean }) {
  const [view, setView] = useState<SubscriptionView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setView(await refreshPlan());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Your plan could not be loaded.");
    }
  }, []);

  useEffect(() => {
    // Loading the plan from the backend is a sync with an external system.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "That did not work. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  const pct = view ? Math.min(100, Math.round((view.usage.used / Math.max(1, view.usage.limit)) * 100)) : 0;
  return (
    <Card>
      <CardHeader
        title="Plan and usage"
        description="Limits are enforced by the platform; one analysis is one property in a calendar month"
        action={view && <Badge tone={view.plan === "free" ? "neutral" : "brand"}>{view.planName}</Badge>}
      />
      <div className="space-y-4 p-5">
        {justUpgraded && view && view.plan !== "free" && (
          <p className="rounded-lg bg-positive-soft px-3 py-2 text-sm text-positive">Your {view.planName} plan is active.</p>
        )}
        {error && (
          <p role="alert" className="rounded-lg border border-negative/20 bg-negative-soft px-3 py-2 text-sm text-negative">
            {error}
          </p>
        )}
        {!view && !error && <div className="h-16 animate-pulse rounded-lg bg-line/60" />}
        {view && (
          <>
            <div>
              <div className="flex justify-between text-sm">
                <span>
                  <span className="tabular font-semibold">{view.usage.used}</span> of {view.usage.limit} analyses used
                </span>
                <span className="text-muted">resets {formatDate(view.usage.resetsOn)}</span>
              </div>
              <div className="mt-2 h-2 overflow-hidden rounded-full bg-line" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
                <div className={pct >= 100 ? "h-full bg-negative" : pct >= 80 ? "h-full bg-warning" : "h-full bg-brand-600"} style={{ width: `${pct}%` }} />
              </div>
            </div>
            <dl className="grid gap-2 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-muted">Status</dt>
                <dd>
                  {view.status === "free" ? "Free plan" : view.status.replace("_", " ")}
                  {view.paymentStatus === "failed" && <span className="text-negative"> · last payment failed, update your card</span>}
                </dd>
              </div>
              {view.currentPeriodEnd && (
                <div>
                  <dt className="text-muted">{view.cancelAtPeriodEnd ? "Ends on" : "Renews on"}</dt>
                  <dd>{formatDate(view.currentPeriodEnd)}</dd>
                </div>
              )}
            </dl>
            {view.teamMember ? (
              <p className="text-sm text-muted">You use your team&apos;s {view.planName} plan; the team owner manages billing.</p>
            ) : (
              <div className="flex flex-wrap gap-2">
                <ButtonLink href="/pricing" variant={view.plan === "free" ? "primary" : "secondary"}>
                  {view.plan === "free" ? "Upgrade" : "Compare plans"}
                </ButtonLink>
                {view.billingMode === "stripe" && view.plan !== "free" && (
                  <Button variant="secondary" disabled={busy} onClick={() => run(async () => window.location.assign((await billingPortal()).url))}>
                    Manage billing
                  </Button>
                )}
                {view.billingMode === "demo" && view.plan !== "free" && (
                  <Button variant="ghost" disabled={busy} onClick={() => run(async () => { await cancelDemoPlan(); await load(); })}>
                    Cancel plan (demo)
                  </Button>
                )}
              </div>
            )}
            {view.billingMode === "demo" && (
              <p className="text-xs text-subtle">Demo billing: plans switch instantly and no payment is taken. Stripe is used when it is configured.</p>
            )}
            {view.features.includes("api-access") && !view.teamMember && <ApiKeys />}
          </>
        )}
      </div>
    </Card>
  );
}

function ApiKeys() {
  const [keys, setKeys] = useState<ApiKeyView[]>([]);
  const [secret, setSecret] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setKeys(await listApiKeys());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "API keys could not be loaded.");
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  async function create() {
    setError(null);
    try {
      const k = await createApiKey(name.trim() || "API key");
      setSecret(k.secret);
      setName("");
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The key could not be created.");
    }
  }

  return (
    <div className="border-t border-line pt-4">
      <p className="text-sm font-semibold">API keys</p>
      <p className="text-xs text-muted">Send a key in the X-API-Key header. Calls count against your plan&apos;s analyses.</p>
      {secret && (
        <p className="mt-2 rounded-lg bg-warning-soft px-3 py-2 text-xs break-all">
          Copy this key now, it is shown only once: <code className="font-mono">{secret}</code>
        </p>
      )}
      <div className="mt-3 flex gap-2">
        <input className="input h-8 flex-1" placeholder="Key name, e.g. CRM import" aria-label="API key name" value={name} onChange={(e) => setName(e.target.value)} />
        <Button size="sm" onClick={create}>
          Create key
        </Button>
      </div>
      {error && <p className="mt-2 text-xs text-negative">{error}</p>}
      <ul className="mt-3 divide-y divide-line text-sm">
        {keys.map((k) => (
          <li key={k.id} className="flex items-center justify-between gap-2 py-2">
            <span>
              {k.name} <code className="text-xs text-muted">{k.prefix}…</code>
              <span className="block text-xs text-subtle">
                {k.revokedAt ? `Revoked ${formatDate(k.revokedAt)}` : k.lastUsedAt ? `Last used ${formatDate(k.lastUsedAt)}` : "Never used"}
              </span>
            </span>
            {!k.revokedAt && (
              <Button variant="ghost" size="sm" onClick={async () => { await revokeApiKey(k.id).catch(() => undefined); await load(); }}>
                Revoke
              </Button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
