"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import type { ReactNode } from "react";
import type { AnalysisResult } from "@/analysis/types";
import { ScoreRing } from "@/components/charts";
import { ButtonLink, Card, EmptyState, PageHeader, SampleNotice } from "@/components/ui";
import { cn, formatEur, formatPct, formatPerSqm } from "@/lib/format";
import { useHydrated } from "@/lib/local-store";
import { useAnalyses, useProperties } from "@/properties/store";
import { PROPERTY_TYPE_LABELS, type Property } from "@/properties/types";
import { PropertyThumb, VerdictBadge } from "@/properties/ui";

const MAX = 4;

type Row = {
  label: string;
  value: (p: Property, a: AnalysisResult) => number | string;
  format?: (v: number) => ReactNode;
  /** Which direction is better, for highlighting the winner. */
  better?: "higher" | "lower";
};

const ROWS: { group: string; rows: Row[] }[] = [
  {
    group: "Price",
    rows: [
      { label: "Asking price", value: (p) => p.askingPrice, format: formatEur, better: "lower" },
      { label: "Estimated value", value: (_, a) => a.estimate.mid, format: formatEur },
      { label: "Asking vs estimate", value: (_, a) => a.deltaPct, format: (v) => formatPct(v, true), better: "lower" },
      { label: "Asking per m²", value: (_, a) => a.pricePerSqm.asking, format: formatPerSqm, better: "lower" },
    ],
  },
  {
    group: "Property",
    rows: [
      { label: "Type", value: (p) => PROPERTY_TYPE_LABELS[p.propertyType] },
      { label: "Size", value: (p) => p.sizeSqm, format: (v) => `${v} m²`, better: "higher" },
      { label: "Bedrooms", value: (p) => p.bedrooms, better: "higher" },
      { label: "Year built", value: (p) => p.renovatedYear ?? p.yearBuilt, better: "higher" },
      { label: "Energy class", value: (p) => p.energyClass },
    ],
  },
  {
    group: "Costs",
    rows: [
      {
        label: "Cash needed",
        value: (_, a) => a.financial.totalAcquisition - a.financial.loanAmount,
        format: formatEur,
        better: "lower",
      },
      { label: "Ownership / month", value: (_, a) => a.financial.monthlyOwnership, format: formatEur, better: "lower" },
      {
        label: "Known repairs",
        value: (_, a) => a.inspection.reduce((s, f) => s + f.estimatedCost, 0),
        format: formatEur,
        better: "lower",
      },
      { label: "Medium renovation", value: (_, a) => a.renovation[1]?.cost ?? 0, format: formatEur, better: "lower" },
    ],
  },
  {
    group: "Investment",
    rows: [
      { label: "Rent / month", value: (_, a) => a.rental.monthlyRent, format: formatEur, better: "higher" },
      { label: "Net yield", value: (_, a) => a.rental.netYieldPct, format: (v) => formatPct(v), better: "higher" },
      { label: "Suggested max offer", value: (_, a) => a.negotiation.maxOffer, format: formatEur },
    ],
  },
  {
    group: "Scores",
    rows: [
      { label: "Location", value: (_, a) => a.scores.location, better: "higher" },
      { label: "Condition", value: (_, a) => a.scores.condition, better: "higher" },
      { label: "Energy", value: (_, a) => a.scores.energy, better: "higher" },
    ],
  },
];

export function CompareView() {
  const hydrated = useHydrated();
  const router = useRouter();
  const params = useSearchParams();
  const properties = useProperties();
  const analyses = useAnalyses();

  if (!hydrated) return <div className="h-96 animate-pulse rounded-xl bg-line/60" aria-busy="true" />;

  const analysed = properties.filter((p) => analyses[p.id]);
  const requested = (params.get("ids") ?? "").split(",").filter(Boolean);
  const ids = (requested.length ? requested : analysed.slice(0, 3).map((p) => p.id))
    .filter((id) => analyses[id] && properties.some((p) => p.id === id))
    .slice(0, MAX);
  const chosen = ids.map((id) => properties.find((p) => p.id === id)!);

  const setIds = (next: string[]) => router.replace(`/compare?ids=${next.join(",")}`, { scroll: false });
  const toggle = (id: string) =>
    setIds(ids.includes(id) ? ids.filter((x) => x !== id) : ids.length < MAX ? [...ids, id] : ids);

  if (analysed.length < 2)
    return (
      <>
        <PageHeader title="Compare properties" />
        <EmptyState
          title="Analyse at least two properties"
          description="Comparison needs two or more analysed properties."
          action={<ButtonLink href="/properties/new">Analyze a property</ButtonLink>}
        />
      </>
    );

  return (
    <>
      <PageHeader
        title="Compare properties"
        description="Side by side on price, costs, investment and condition. The best value in each row is highlighted."
      />
      <SampleNotice className="mb-5" />

      <div className="no-print mb-5 flex flex-wrap gap-2" role="group" aria-label="Choose properties to compare">
        {analysed.map((p) => {
          const on = ids.includes(p.id);
          return (
            <button
              key={p.id}
              onClick={() => toggle(p.id)}
              aria-pressed={on}
              disabled={!on && ids.length >= MAX}
              className={cn(
                "rounded-full border px-3 py-1 text-sm transition-colors disabled:opacity-40",
                on
                  ? "border-ink-900 bg-ink-900 text-white"
                  : "border-line-strong bg-surface text-muted hover:text-text",
              )}
            >
              {p.title}
            </button>
          );
        })}
      </div>

      {chosen.length < 2 ? (
        <p className="py-10 text-center text-sm text-muted">Select at least two properties above.</p>
      ) : (
        <Card className="overflow-x-auto">
          <table className="w-full min-w-[40rem] text-sm">
            <thead>
              <tr className="border-b border-line">
                <th className="w-44 px-5 py-4 text-left align-bottom text-xs font-medium tracking-wide text-muted uppercase">
                  Property
                </th>
                {chosen.map((p) => {
                  const a = analyses[p.id];
                  return (
                    <th key={p.id} className="px-4 py-4 text-left align-top font-normal">
                      <PropertyThumb property={p} className="mb-3 aspect-[16/9] w-full" />
                      <Link href={`/properties/${p.id}`} className="block font-semibold hover:underline">
                        {p.title}
                      </Link>
                      <p className="text-xs text-muted">{p.district}</p>
                      <div className="mt-3 flex items-center gap-3">
                        <ScoreRing value={a.scores.overall} size={56} />
                        <VerdictBadge verdict={a.verdict} />
                      </div>
                    </th>
                  );
                })}
              </tr>
            </thead>
            {ROWS.map(({ group, rows }) => (
              <tbody key={group}>
                <tr>
                  <th
                    colSpan={chosen.length + 1}
                    className="bg-canvas px-5 py-2 text-left text-xs font-semibold tracking-wide text-muted uppercase"
                  >
                    {group}
                  </th>
                </tr>
                {rows.map((row) => {
                  const values = chosen.map((p) => row.value(p, analyses[p.id]));
                  const numeric = values.filter((v): v is number => typeof v === "number");
                  const best =
                    row.better && numeric.length === values.length && new Set(numeric).size > 1
                      ? row.better === "higher"
                        ? Math.max(...numeric)
                        : Math.min(...numeric)
                      : undefined;
                  return (
                    <tr key={row.label} className="border-t border-line">
                      <th className="px-5 py-2.5 text-left font-normal text-muted">{row.label}</th>
                      {values.map((v, i) => (
                        <td
                          key={chosen[i].id}
                          className={cn("tabular px-4 py-2.5", v === best && "font-semibold text-positive")}
                        >
                          {typeof v === "number" && row.format ? row.format(v) : v}
                          {v === best && <span className="sr-only"> (best)</span>}
                        </td>
                      ))}
                    </tr>
                  );
                })}
              </tbody>
            ))}
          </table>
        </Card>
      )}
    </>
  );
}
