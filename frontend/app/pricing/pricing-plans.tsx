"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { IconCheck } from "@/components/icons";
import { Badge, Button, Card } from "@/components/ui";
import { cn } from "@/lib/format";
import { sessionStore, useSession, type PlanId } from "@/lib/session";

type Plan = {
  id: PlanId;
  name: string;
  monthly: number;
  audience: string;
  features: string[];
  highlighted?: boolean;
};

export const PLANS: Plan[] = [
  {
    id: "free",
    name: "Free",
    monthly: 0,
    audience: "Trying the platform on a first property.",
    features: ["3 analyses per month", "Valuation range and verdict", "Financial snapshot", "Save up to 5 properties"],
  },
  {
    id: "premium",
    name: "Premium",
    monthly: 9,
    audience: "Active buyers comparing several homes.",
    features: [
      "25 analyses per month",
      "AI Home Inspector on photographs",
      "Renovation & negotiation guidance",
      "Compare up to 4 properties",
      "Downloadable reports",
    ],
    highlighted: true,
  },
  {
    id: "professional",
    name: "Professional",
    monthly: 29,
    audience: "Investors and agents evaluating for clients.",
    features: [
      "150 analyses per month",
      "Everything in Premium",
      "Investment scenarios & ROI",
      "Document Checker",
      "Branded reports",
    ],
  },
  {
    id: "business",
    name: "Business",
    monthly: 99,
    audience: "Agencies, valuers and lenders.",
    features: ["Unlimited analyses", "Team seats & roles", "API access", "Priority support", "Custom data sources"],
  },
];

export function PricingPlans() {
  const [annual, setAnnual] = useState(true);
  const session = useSession();
  const router = useRouter();

  function choose(plan: PlanId) {
    if (!session) {
      router.push("/register");
      return;
    }
    // Prototype: switch the plan locally. Phase 4+ routes this through Stripe via the backend.
    sessionStore.set({ ...session, plan });
    router.push("/dashboard");
  }

  return (
    <>
      <div className="mt-8 flex justify-center">
        <div
          className="inline-flex rounded-lg border border-line bg-surface p-1 text-sm"
          role="group"
          aria-label="Billing period"
        >
          {[
            [false, "Monthly"],
            [true, "Annual · 2 months free"],
          ].map(([value, label]) => (
            <button
              key={String(value)}
              onClick={() => setAnnual(value as boolean)}
              aria-pressed={annual === value}
              className={cn(
                "rounded-md px-4 py-1.5 font-medium transition-colors",
                annual === value ? "bg-ink-900 text-white" : "text-muted hover:text-text",
              )}
            >
              {label as string}
            </button>
          ))}
        </div>
      </div>

      <div className="mt-10 grid gap-5 md:grid-cols-2 lg:grid-cols-4">
        {PLANS.map((plan) => {
          const price = annual ? Math.round((plan.monthly * 10) / 12) : plan.monthly;
          const current = session?.plan === plan.id;
          return (
            <Card
              key={plan.id}
              className={cn("flex flex-col p-6", plan.highlighted && "border-ink-900 ring-1 ring-ink-900")}
            >
              <div className="flex items-center justify-between">
                <h2 className="text-lg font-semibold">{plan.name}</h2>
                {plan.highlighted && <Badge tone="brand">Most popular</Badge>}
                {current && <Badge tone="positive">Current</Badge>}
              </div>
              <p className="mt-1 min-h-10 text-sm text-muted">{plan.audience}</p>
              <p className="mt-5 flex items-baseline gap-1">
                <span className="tabular text-4xl font-semibold tracking-tight">€{price}</span>
                <span className="text-sm text-muted">/ month</span>
              </p>
              <p className="mt-1 h-5 text-xs text-subtle">
                {plan.monthly > 0 && annual
                  ? `€${plan.monthly * 10} billed yearly`
                  : plan.monthly > 0
                    ? "Billed monthly"
                    : ""}
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
                disabled={current}
              >
                {current ? "Your plan" : plan.monthly === 0 ? "Start free" : `Choose ${plan.name}`}
              </Button>
            </Card>
          );
        })}
      </div>
      <p className="mt-6 text-center text-xs text-subtle">
        Prices in EUR including VAT. Payments are not live in this prototype — choosing a plan switches it locally.
      </p>
    </>
  );
}
