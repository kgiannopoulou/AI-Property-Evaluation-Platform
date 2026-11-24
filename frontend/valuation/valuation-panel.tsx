"use client";

import { useState } from "react";
import type { InspectionFinding } from "@/analysis/types";
import { IconSpark, IconTrash } from "@/components/icons";
import { Badge, Button, ButtonLink, Card, CardHeader, Stat } from "@/components/ui";
import { useAiInspections } from "@/inspection/api";
import { API_URL, ApiError } from "@/lib/api";
import { cn, formatDate, formatEur } from "@/lib/format";
import type { Property } from "@/properties/types";
import { runValuation, setRepairQuotes, useRepairQuotes, useValuations } from "./api";
import { buildValuationRequest } from "./request";
import type { ComparableResult, RepairQuote, Score, ValuationReport } from "./types";

const GRADE_TONE = { high: "positive", medium: "brand", low: "warning", insufficient: "negative" } as const;
const SEVERITY_TONE: Record<string, "neutral" | "warning" | "negative" | "brand"> = {
  critical: "negative",
  high: "negative",
  medium: "warning",
  low: "neutral",
  info: "brand",
};
const LISTING_LABEL = { sold: "Sold", "sale-listing": "Asking", rented: "Let", "rent-listing": "Asking" } as const;

/**
 * The comparable-sales valuation engine (Java backend). It shows a fair-value range only when the
 * market data supports one; otherwise it says what is missing.
 */
export function ValuationPanel({ property, ruleFindings }: { property: Property; ruleFindings: InspectionFinding[] }) {
  const report = useValuations()[property.id];
  const quotes = useRepairQuotes()[property.id] ?? [];
  const ai = useAiInspections()[property.id];
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const rent = property.listingType === "rent";
  const money = (n: number) => `${formatEur(n)}${rent ? "/month" : ""}`;

  async function run() {
    setRunning(true);
    setError(null);
    try {
      await runValuation(
        property.id,
        buildValuationRequest(property, { aiFindings: ai?.findings, ruleFindings, repairQuotes: quotes }),
      );
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError("The valuation failed. Please try again."));
    } finally {
      setRunning(false);
    }
  }

  return (
    <Card>
      <CardHeader
        title="Valuation engine"
        description={`Fair ${rent ? "market rent" : "market value"} from comparable ${rent ? "lettings" : "sales"}, not from an AI guess`}
        action={
          API_URL ? (
            <Button onClick={run} disabled={running} className="no-print">
              <IconSpark /> {running ? "Valuing…" : report ? "Run again" : "Run valuation"}
            </Button>
          ) : null
        }
      />
      <div className="space-y-6 p-5">
        {!API_URL && (
          <p className="text-sm text-muted">
            The valuation engine runs in the backend. Start it with a market database and set{" "}
            <code className="rounded bg-canvas px-1">NEXT_PUBLIC_API_URL</code>. The figures elsewhere on this page come
            from the illustrative sample engine.
          </p>
        )}
        {error && (
          <p
            role="alert"
            className="rounded-lg border border-negative/20 bg-negative-soft px-3 py-2 text-sm text-negative"
          >
            {error.message}{" "}
            {(error.code === "quota-exceeded" || error.code === "upgrade-required") && (
              <a href="/pricing" className="font-medium underline">
                See plans
              </a>
            )}
            {error.code === "unauthenticated" && (
              <a href="/login" className="font-medium underline">
                Sign in
              </a>
            )}
          </p>
        )}

        {report && (report.status === "valued" ? <Valued r={report} money={money} /> : <Insufficient r={report} />)}

        <RepairQuotes propertyId={property.id} quotes={quotes} />

        {report && (
          <>
            <Comparables rows={report.comparables} rent={rent} />
            <details className="text-sm">
              <summary className="cursor-pointer font-medium">How the engine works</summary>
              <ul className="mt-2 list-disc space-y-1 pl-5 text-muted">
                {report.methodology.map((m) => (
                  <li key={m}>{m}</li>
                ))}
              </ul>
            </details>
            <p className="text-xs text-subtle">
              {report.disclaimer} Generated {formatDate(report.generatedAt)} by {report.engine}.
            </p>
          </>
        )}
      </div>
    </Card>
  );
}

function Valued({ r, money }: { r: ValuationReport; money: (n: number) => string }) {
  const e = r.estimate!;
  const n = r.negotiation;
  const q = r.dataQuality;
  return (
    <>
      <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label={r.mode === "rent" ? "Asking rent" : "Asking price"} value={money(r.askingPrice)} />
        <Stat
          label={r.mode === "rent" ? "Fair market rent" : "Estimated fair market value"}
          value={`${formatEur(e.low)}–${formatEur(e.high)}`}
          hint={
            r.verdict === "above-market"
              ? `Asking is ${r.deltaPct}% above the midpoint`
              : r.verdict === "below-market"
                ? `Asking is ${Math.abs(r.deltaPct ?? 0)}% below the midpoint`
                : "Asking is within the range"
          }
          tone={r.verdict === "above-market" ? "negative" : r.verdict === "below-market" ? "positive" : undefined}
        />
        <ScoreStat label="Condition score" s={r.conditionScore} />
        <ScoreStat label="Location score" s={r.locationScore} />
      </div>

      <DataQualityBox r={r} />

      <Concerns r={r} />

      {!n ? (
        <div className="rounded-lg border border-brand-100 bg-brand-50 p-4">
          <p className="text-sm font-semibold">Comparable evidence and negotiation strategy</p>
          <p className="mt-1 text-sm text-muted">
            The comparables behind this range, their adjustments, the suggested offers and the evidence for the maximum
            offer are part of Premium.
          </p>
          <ButtonLink href="/pricing" size="sm" className="mt-3">
            See plans
          </ButtonLink>
        </div>
      ) : (
        <div className="rounded-lg bg-canvas p-4">
          <p className="text-sm font-semibold">Suggested negotiation strategy</p>
          <p className="mt-1 text-sm">{n.strategy}</p>
          <dl className="mt-3 grid grid-cols-3 gap-3 text-sm">
            {[
              ["Opening offer", n.openingOffer],
              ["Target", n.targetPrice],
              ["Maximum offer", n.maxOffer],
            ].map(([label, v]) => (
              <div key={label as string}>
                <dt className="text-xs text-muted">{label}</dt>
                <dd className="tabular font-semibold">{money(v as number)}</dd>
              </div>
            ))}
          </dl>
          <p className="mt-4 text-xs font-medium tracking-wide text-muted uppercase">
            Evidence behind the maximum offer
          </p>
          <ul className="mt-1 list-disc space-y-1 pl-5 text-sm">
            {n.evidence.map((x) => (
              <li key={x}>{x}</li>
            ))}
          </ul>
          {q.grade === "low" && (
            <p className="mt-2 text-xs text-warning">Data quality is low: use these figures as indicative only.</p>
          )}
        </div>
      )}
    </>
  );
}

function Insufficient({ r }: { r: ValuationReport }) {
  const q = r.dataQuality;
  return (
    <>
      <div className="rounded-lg border border-warning/30 bg-warning-soft p-4">
        <p className="text-sm font-semibold text-warning">Not enough reliable data for a valuation</p>
        <p className="mt-1 text-sm">
          The engine does not show a price range, verdict or offer when the evidence cannot support one.
        </p>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
          {q.reasons.map((x) => (
            <li key={x}>{x}</li>
          ))}
        </ul>
        {q.nextSteps.length > 0 && (
          <>
            <p className="mt-3 text-sm font-medium">What would help</p>
            <ul className="mt-1 list-disc space-y-1 pl-5 text-sm">
              {q.nextSteps.map((x) => (
                <li key={x}>{x}</li>
              ))}
            </ul>
          </>
        )}
      </div>
      <div className="grid gap-6 sm:grid-cols-2">
        <ScoreStat label="Condition score" s={r.conditionScore} />
        <ScoreStat label="Location score" s={r.locationScore} />
      </div>
      <Concerns r={r} />
    </>
  );
}

function ScoreStat({ label, s }: { label: string; s: Score }) {
  return (
    <div>
      <Stat
        label={label}
        value={s.score === null ? "—" : `${s.score}/100`}
        hint={s.score === null ? s.basis : undefined}
      />
      {s.items.length > 0 && (
        <details className="mt-1 text-xs text-muted">
          <summary className="cursor-pointer">Breakdown</summary>
          <p className="mt-1">{s.basis}</p>
          <ul className="mt-1 space-y-0.5">
            {s.items.map((i) => (
              <li key={i.label + i.detail} className="flex justify-between gap-2">
                <span>
                  {i.label}: {i.detail}
                </span>
                <span className="tabular">{i.points > 0 ? `+${i.points}` : i.points}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}

function DataQualityBox({ r }: { r: ValuationReport }) {
  const q = r.dataQuality;
  const m = q.metrics;
  return (
    <div className="rounded-lg border border-line p-4">
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-sm font-semibold">Data quality</p>
        <Badge tone={GRADE_TONE[q.grade]}>
          {q.grade} · {q.score}/100
        </Badge>
      </div>
      <p className="mt-1 text-sm text-muted">
        {m.used} comparables used of {m.candidates} found ({m.transactions} completed, {m.askingPrices} asking)
        {m.searchRadiusM ? ` within ${m.searchRadiusM / 1000} km` : ", matched by district"}
        {m.medianAgeMonths !== null ? `, median ${m.medianAgeMonths} months old` : ""}
        {m.dispersionPct !== null ? `, spread ±${m.dispersionPct}%` : ""}
        {m.attributeCoveragePct !== null ? `, ${m.attributeCoveragePct}% of attributes recorded` : ""}.
      </p>
      {q.warnings.length > 0 && (
        <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-warning">
          {q.warnings.map((w) => (
            <li key={w}>{w}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Concerns({ r }: { r: ValuationReport }) {
  if (r.concerns.length === 0) return null;
  return (
    <div>
      <p className="text-sm font-semibold">Potential concerns</p>
      <ul className="mt-2 space-y-1.5">
        {r.concerns.map((c) => (
          <li key={c.title} className="flex flex-wrap items-center gap-2 text-sm">
            <Badge tone={SEVERITY_TONE[c.severity] ?? "neutral"}>{c.severity}</Badge>
            <span>{c.title}</span>
            <span className="text-xs text-subtle">
              {c.observed ? "observed" : "to check"} · {c.source}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Comparables({ rows, rent }: { rows: ComparableResult[]; rent: boolean }) {
  if (rows.length === 0) return null;
  const perSqm = (v: number) => (rent ? `€${v.toFixed(1)}` : formatEur(v));
  return (
    <div>
      <p className="text-sm font-semibold">Comparables considered</p>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="text-xs text-muted">
            <tr>
              <th className="py-1.5 pr-3 font-medium">Record</th>
              <th className="py-1.5 pr-3 font-medium">Size</th>
              <th className="py-1.5 pr-3 font-medium">Price</th>
              <th className="py-1.5 pr-3 font-medium">€/m²</th>
              <th className="py-1.5 pr-3 font-medium">Adjusted €/m²</th>
              <th className="py-1.5 font-medium">Weight / reason</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {rows.map((c) => (
              <tr key={c.id} className={cn("align-top", c.excludedReason && "text-subtle")}>
                <td className="py-2 pr-3">
                  <span className="font-medium">{LISTING_LABEL[c.listingType]}</span> {formatDate(c.observedOn)}
                  {c.distanceM !== null && <span className="text-xs text-muted"> · {Math.round(c.distanceM)} m</span>}
                  {c.address && <span className="block text-xs text-muted">{c.address}</span>}
                  {c.adjustments.length > 0 && (
                    <details className="text-xs text-muted">
                      <summary className="cursor-pointer">{c.adjustments.length} adjustments</summary>
                      <ul>
                        {c.adjustments.map((a) => (
                          <li key={a.factor}>
                            {a.factor} {a.pct > 0 ? "+" : ""}
                            {a.pct}%: {a.detail}
                          </li>
                        ))}
                      </ul>
                    </details>
                  )}
                </td>
                <td className="tabular py-2 pr-3">{c.sizeSqm} m²</td>
                <td className="tabular py-2 pr-3">{formatEur(c.price)}</td>
                <td className="tabular py-2 pr-3">{perSqm(c.pricePerSqm)}</td>
                <td className="tabular py-2 pr-3">
                  {c.adjustedPricePerSqm !== null ? perSqm(c.adjustedPricePerSqm) : "—"}
                </td>
                <td className="py-2 text-xs">
                  {c.excludedReason ?? <span className="tabular">{Math.round((c.weight ?? 0) * 100)}%</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function RepairQuotes({ propertyId, quotes }: { propertyId: string; quotes: RepairQuote[] }) {
  const update = (id: string, patch: Partial<RepairQuote>) =>
    setRepairQuotes(
      propertyId,
      quotes.map((q) => (q.id === id ? { ...q, ...patch } : q)),
    );
  const add = () =>
    setRepairQuotes(propertyId, [
      ...quotes,
      { id: `q-${Date.now().toString(36)}`, item: "", low: 0, high: 0, source: "", verified: false },
    ]);
  const input = "h-8 rounded-md border border-line-strong bg-surface px-2 text-sm";
  return (
    <div className="no-print">
      <div className="flex items-center justify-between gap-2">
        <div>
          <p className="text-sm font-semibold">Repair estimates</p>
          <p className="text-xs text-muted">
            Tick “verified” only for a written quote or survey. Verified costs lower the suggested offers; the rest are
            listed but not deducted.
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={add} disabled={quotes.length >= 30}>
          Add estimate
        </Button>
      </div>
      {quotes.length > 0 && (
        <ul className="mt-2 space-y-2">
          {quotes.map((q) => (
            <li key={q.id} className="flex flex-wrap items-center gap-2">
              <input
                className={cn(input, "min-w-40 flex-1")}
                placeholder="Item, e.g. bathroom renovation"
                aria-label="Repair item"
                value={q.item}
                onChange={(e) => update(q.id, { item: e.target.value })}
              />
              <input
                className={cn(input, "w-24")}
                type="number"
                min={0}
                aria-label="Lowest cost, EUR"
                placeholder="From €"
                value={q.low || ""}
                onChange={(e) => update(q.id, { low: Number(e.target.value) })}
              />
              <input
                className={cn(input, "w-24")}
                type="number"
                min={0}
                aria-label="Highest cost, EUR"
                placeholder="To €"
                value={q.high || ""}
                onChange={(e) => update(q.id, { high: Number(e.target.value) })}
              />
              <input
                className={cn(input, "w-44")}
                placeholder="Source"
                aria-label="Source of the estimate"
                value={q.source}
                onChange={(e) => update(q.id, { source: e.target.value })}
              />
              <label className="flex items-center gap-1 text-sm">
                <input
                  type="checkbox"
                  checked={q.verified}
                  onChange={(e) => update(q.id, { verified: e.target.checked })}
                />
                verified
              </label>
              <Button
                variant="ghost"
                size="sm"
                aria-label="Remove estimate"
                onClick={() =>
                  setRepairQuotes(
                    propertyId,
                    quotes.filter((x) => x.id !== q.id),
                  )
                }
              >
                <IconTrash />
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
