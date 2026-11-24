"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { IconCheck } from "@/components/icons";
import { Badge, Button, Card, Field } from "@/components/ui";
import { businessEnquiry, checkout, refreshPlan } from "@/lib/account";
import { API_URL, ApiError } from "@/lib/api";
import { cn } from "@/lib/format";
import { PLANS, formatPlanPrice, type PlanId } from "@/lib/plans";
import { sessionStore, useSession } from "@/lib/session";

export function PricingPlans() {
  const session = useSession();
  const router = useRouter();
  const [busy, setBusy] = useState<PlanId | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [enquiry, setEnquiry] = useState(false);
  const backend = Boolean(API_URL && session?.token);

  async function choose(plan: PlanId) {
    setError(null);
    if (!session) {
      router.push("/register");
      return;
    }
    if (plan === "business") {
      setEnquiry(true);
      return;
    }
    if (!backend) {
      // Browser-only demo: switch the plan locally.
      sessionStore.set({ ...session, plan });
      router.push("/dashboard");
      return;
    }
    if (plan === "free") {
      router.push("/settings");
      return;
    }
    setBusy(plan);
    try {
      const result = await checkout(plan);
      if (result.mode === "demo") {
        await refreshPlan();
        router.push("/settings?billing=success");
      } else {
        window.location.assign(result.url); // Stripe Checkout
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Checkout could not start. Please try again.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <div className="mt-10 grid gap-5 md:grid-cols-2 lg:grid-cols-4">
        {PLANS.map((plan) => {
          const current = session?.plan === plan.id;
          return (
            <Card key={plan.id} className={cn("flex flex-col p-6", plan.highlighted && "border-ink-900 ring-1 ring-ink-900")}>
              <div className="flex items-center justify-between">
                <h2 className="text-lg font-semibold">{plan.name}</h2>
                {plan.highlighted && !current && <Badge tone="brand">Most popular</Badge>}
                {current && <Badge tone="positive">Current</Badge>}
              </div>
              <p className="mt-1 min-h-10 text-sm text-muted">{plan.audience}</p>
              <p className="mt-5 flex items-baseline gap-1">
                <span className="tabular text-4xl font-semibold tracking-tight">{formatPlanPrice(plan)}</span>
                {plan.monthlyPriceCents !== null && <span className="text-sm text-muted">/ month</span>}
              </p>
              <p className="mt-1 h-5 text-xs text-subtle">
                {plan.monthlyPriceCents === null ? "Priced by contract" : plan.monthlyPriceCents > 0 ? "Billed monthly, cancel any time" : ""}
              </p>
              <ul className="mt-6 flex-1 space-y-2.5 text-sm">
                {plan.features.map((f) => (
                  <li key={f} className="flex gap-2">
                    <IconCheck className="mt-0.5 shrink-0 text-brand-600" width={16} height={16} />
                    {f}
                  </li>
                ))}
              </ul>
              <Button
                className="mt-7 w-full"
                variant={plan.highlighted ? "primary" : "secondary"}
                onClick={() => choose(plan.id)}
                disabled={current || busy !== null}
              >
                {current
                  ? "Your plan"
                  : busy === plan.id
                    ? "Opening checkout…"
                    : plan.id === "free"
                      ? "Start free"
                      : plan.id === "business"
                        ? "Contact us"
                        : `Choose ${plan.name}`}
              </Button>
            </Card>
          );
        })}
      </div>
      {error && (
        <p role="alert" className="mx-auto mt-6 max-w-xl rounded-lg border border-negative/20 bg-negative-soft px-3 py-2 text-center text-sm text-negative">
          {error}
        </p>
      )}
      {enquiry && <BusinessEnquiry backend={backend} defaultEmail={session?.email ?? ""} onClose={() => setEnquiry(false)} />}
      <p className="mt-6 text-center text-xs text-subtle">
        Prices in EUR including VAT. These are initial prices we are testing and may change. One analysis is one property
        analysed in a calendar month; re-running it costs nothing.{" "}
        {API_URL ? "Payments are processed by Stripe; card details never reach our servers." : "Payments are not live in this browser-only demo."}
      </p>
    </>
  );
}

function BusinessEnquiry({ backend, defaultEmail, onClose }: { backend: boolean; defaultEmail: string; onClose: () => void }) {
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const d = new FormData(e.currentTarget);
    const num = (k: string) => (d.get(k) ? Number(d.get(k)) : undefined);
    if (!backend) {
      setError("Connect the backend and sign in to send an enquiry.");
      return;
    }
    try {
      await businessEnquiry({
        company: String(d.get("company") ?? ""),
        contactEmail: String(d.get("email") ?? ""),
        seats: num("seats"),
        monthlyAnalyses: num("analyses"),
        message: String(d.get("message") ?? "") || undefined,
      });
      setSent(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "The enquiry could not be sent.");
    }
  }

  return (
    <Card className="mx-auto mt-8 max-w-xl p-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold">Business plan enquiry</h2>
          <p className="mt-1 text-sm text-muted">Tell us about your team; we agree seats and monthly analyses by contract.</p>
        </div>
        <Button variant="ghost" size="sm" onClick={onClose}>
          Close
        </Button>
      </div>
      {sent ? (
        <p className="mt-4 text-sm text-positive">Thank you. We will contact you about a Business plan.</p>
      ) : (
        <form onSubmit={onSubmit} className="mt-4 grid gap-4 sm:grid-cols-2">
          <Field label="Company" htmlFor="company">
            <input id="company" name="company" required maxLength={120} className="input" />
          </Field>
          <Field label="Contact email" htmlFor="email">
            <input id="email" name="email" type="email" required defaultValue={defaultEmail} className="input" />
          </Field>
          <Field label="Seats" htmlFor="seats">
            <input id="seats" name="seats" type="number" min={1} max={500} className="input" />
          </Field>
          <Field label="Analyses per month" htmlFor="analyses">
            <input id="analyses" name="analyses" type="number" min={1} className="input" />
          </Field>
          <div className="sm:col-span-2">
            <Field label="What do you need?" htmlFor="message">
              <textarea id="message" name="message" rows={3} maxLength={2000} className="input h-auto py-2" />
            </Field>
          </div>
          {error && <p className="text-sm text-negative sm:col-span-2">{error}</p>}
          <div className="sm:col-span-2">
            <Button type="submit">Send enquiry</Button>
          </div>
        </form>
      )}
    </Card>
  );
}
