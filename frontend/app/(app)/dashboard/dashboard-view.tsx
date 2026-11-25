"use client";

import Link from "next/link";
import { Meter, ScoreRing } from "@/components/charts";
import { IconArrowRight, IconPlus } from "@/components/icons";
import { ButtonLink, Card, CardHeader, EmptyState, PageHeader, Stat } from "@/components/ui";
import { formatDate, formatEurCompact } from "@/lib/format";
import { useHydrated } from "@/lib/local-store";
import { MODULE_STATUS_LABELS, MODULES } from "@/lib/modules";
import { DEMO_SESSION, PLAN_LIMITS, useSession } from "@/lib/session";
import { useAnalyses, useProperties } from "@/properties/store";
import { PropertyThumb, SampleTag, VerdictBadge, formatAsking } from "@/properties/ui";

export function DashboardView() {
  const hydrated = useHydrated();
  const session = useSession();
  const user = session ?? DEMO_SESSION;
  const properties = useProperties();
  const analyses = useAnalyses();

  const analysed = properties.filter((p) => analyses[p.id]);
  const recent = [...analysed]
    .sort((a, b) => analyses[b.id].generatedAt.localeCompare(analyses[a.id].generatedAt))
    .slice(0, 5);

  const limit = PLAN_LIMITS[user.plan].analysesPerMonth;

  // Sale prices and monthly rents are not added together.
  const forSale = properties.filter((p) => p.listingType === "sale");
  const totalAsking = forSale.reduce((s, p) => s + p.askingPrice, 0);
  const avgScore = analysed.length
    ? Math.round(analysed.reduce((s, p) => s + analyses[p.id].scores.overall, 0) / analysed.length)
    : 0;
  const overpriced = analysed.filter((p) => analyses[p.id].verdict === "above-market").length;
  const potentialSaving = analysed.reduce(
    (s, p) =>
      s + (p.listingType === "sale" ? Math.max(0, p.askingPrice - analyses[p.id].negotiation.maxOffer) : 0),
    0,
  );

  if (!hydrated) return <DashboardSkeleton />;

  // Read the clock only in the browser (after hydration), never during prerender.
  const now = new Date();
  const thisMonth = now.toISOString().slice(0, 7);
  const usedThisMonth = properties.filter(
    (p) => !p.isSample && analyses[p.id]?.generatedAt.slice(0, 7) === thisMonth,
  ).length;

  return (
    <>
      <PageHeader
        eyebrow={formatDate(now.toISOString())}
        title={`Welcome${session ? `, ${user.name.split(" ")[0]}` : ""}`}
        description="Your recent analyses, saved properties and plan usage."
        actions={
          <ButtonLink href="/properties/new">
            <IconPlus /> Analyze a property
          </ButtonLink>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card className="p-5">
          <Stat label="Saved properties" value={properties.length} hint={`${analysed.length} analysed`} />
        </Card>
        <Card className="p-5">
          <Stat label="Combined asking" value={formatEurCompact(totalAsking)} hint="Across properties for sale" />
        </Card>
        <Card className="p-5">
          <Stat
            label="Priced above estimate"
            value={overpriced}
            hint={overpriced ? "Room to negotiate" : "None so far"}
            tone={overpriced ? "negative" : undefined}
          />
        </Card>
        <Card className="p-5">
          <Stat
            label="Negotiation headroom"
            value={formatEurCompact(potentialSaving)}
            hint="Asking minus suggested maximum"
            tone={potentialSaving ? "positive" : undefined}
          />
        </Card>
      </div>

      <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Card>
          <CardHeader
            title="Recent analyses"
            description="Latest evaluation for each property"
            action={
              <Link href="/properties" className="text-sm font-medium text-brand-700 hover:underline">
                View all
              </Link>
            }
          />
          {recent.length === 0 ? (
            <div className="p-5">
              <EmptyState
                title="No analyses yet"
                description="Add a property to get a valuation range, costs and negotiation guidance."
                action={<ButtonLink href="/properties/new">Analyze a property</ButtonLink>}
              />
            </div>
          ) : (
            <ul className="divide-y divide-line">
              {recent.map((p) => {
                const a = analyses[p.id];
                return (
                  <li key={p.id}>
                    <Link
                      href={`/properties/${p.id}`}
                      className="flex items-center gap-4 px-5 py-3.5 transition-colors hover:bg-canvas/70"
                    >
                      <PropertyThumb property={p} className="h-12 w-16 shrink-0" />
                      <div className="min-w-0 flex-1">
                        <p className="flex items-center gap-2 truncate text-sm font-medium">
                          <span className="truncate">{p.title}</span>
                          {p.isSample && <SampleTag />}
                        </p>
                        <p className="truncate text-xs text-muted">
                          {p.district} · {p.sizeSqm} m² · analysed {formatDate(a.generatedAt)}
                        </p>
                      </div>
                      <div className="hidden text-right sm:block">
                        <p className="tabular text-sm font-semibold">{formatAsking(p)}</p>
                        <p className="tabular text-xs text-muted">est. {formatEurCompact(a.estimate.mid)}</p>
                      </div>
                      <div className="hidden md:block">
                        <VerdictBadge verdict={a.verdict} deltaPct={a.deltaPct} />
                      </div>
                      <IconArrowRight className="shrink-0 text-subtle" />
                    </Link>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>

        <div className="space-y-6">
          <Card>
            <CardHeader title="Account" description={session ? user.email : "Demo account"} />
            <div className="space-y-4 p-5">
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted">Plan</span>
                <span className="font-medium">{PLAN_LIMITS[user.plan].label}</span>
              </div>
              {limit === null ? (
                <p className="text-sm text-muted">Unlimited analyses on this plan.</p>
              ) : (
                <Meter label="Analyses this month" value={usedThisMonth} max={limit} />
              )}
              <ButtonLink href="/pricing" variant="secondary" size="sm" className="w-full">
                Compare plans
              </ButtonLink>
            </div>
          </Card>

          {analysed.length > 0 && (
            <Card className="flex items-center gap-5 p-5">
              <ScoreRing value={avgScore} size={88} />
              <div>
                <p className="text-sm font-semibold">Average property score</p>
                <p className="mt-0.5 text-sm text-muted">Price, location, condition and energy, weighted.</p>
              </div>
            </Card>
          )}
        </div>
      </div>

      <Card className="mt-6">
        <CardHeader title="Modules" description="Engine: runs in the backend · Preview: sample report in the browser" />
        <ul className="grid gap-px overflow-hidden rounded-b-xl bg-line sm:grid-cols-2 lg:grid-cols-3">
          {MODULES.map((m) => (
            <li key={m.name} className="bg-surface px-5 py-4">
              <p className="flex items-center justify-between gap-2 text-sm font-medium">
                {m.name}
                <span
                  className={
                    m.status === "planned"
                      ? "text-[11px] font-semibold tracking-wide text-subtle uppercase"
                      : "text-[11px] font-semibold tracking-wide text-brand-700 uppercase"
                  }
                >
                  {MODULE_STATUS_LABELS[m.status]}
                </span>
              </p>
              <p className="mt-1 text-xs leading-relaxed text-muted">{m.description}</p>
            </li>
          ))}
        </ul>
      </Card>
    </>
  );
}

function DashboardSkeleton() {
  return (
    <div className="animate-pulse space-y-6" aria-busy="true" aria-label="Loading dashboard">
      <div className="h-8 w-56 rounded bg-line" />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-24 rounded-xl bg-line/60" />
        ))}
      </div>
      <div className="h-72 rounded-xl bg-line/60" />
    </div>
  );
}
