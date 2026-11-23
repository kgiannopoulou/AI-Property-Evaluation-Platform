"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import type { AnalysisResult, InspectionSeverity } from "@/analysis/types";
import { FactorBars, Meter, PricePerSqmBars, ScoreRing, StackedBar, ValueRangeBar } from "@/components/charts";
import { IconCheck, IconDownload, IconSpark, IconTrash } from "@/components/icons";
import {
  Badge,
  Button,
  ButtonLink,
  Card,
  CardHeader,
  EmptyState,
  PageHeader,
  SampleNotice,
  Stat,
} from "@/components/ui";
import { cn, formatDate, formatEur, formatPct, formatPerSqm, formatRentPerSqm } from "@/lib/format";
import { useHydrated } from "@/lib/local-store";
import { deleteProperty, runAnalysis, useAnalyses, useProperties } from "@/properties/store";
import { photosByArea } from "@/properties/submission";
import {
  APPLIANCES,
  APPLIANCE_LABELS,
  APPLIANCE_STATUS_LABELS,
  CERTIFICATE_LABELS,
  CONDITION_LABELS,
  CONSTRUCTION_LABELS,
  COOLING_LABELS,
  FACILITY_LABELS,
  FURNITURE_STATUS_LABELS,
  HEATING_LABELS,
  HEATING_STATUS_LABELS,
  LISTING_TYPE_LABELS,
  PHOTO_AREA_LABELS,
  PROPERTY_TYPE_LABELS,
  type ApplianceStatus,
  type HeatingStatus,
  type Property,
} from "@/properties/types";
import { SampleTag, VerdictBadge, formatAsking } from "@/properties/ui";

const TABS = [
  ["overview", "Overview"],
  ["inspection", "Inspection"],
  ["costs", "Costs & investment"],
  ["renovation", "Renovation"],
  ["negotiation", "Negotiation"],
] as const;
type Tab = (typeof TABS)[number][0];

export function PropertyReport({ id }: { id: string }) {
  const hydrated = useHydrated();
  const router = useRouter();
  const properties = useProperties();
  const analyses = useAnalyses();
  const [tab, setTab] = useState<Tab>("overview");
  const [confirmDelete, setConfirmDelete] = useState(false);

  if (!hydrated) return <div className="h-96 animate-pulse rounded-xl bg-line/60" aria-busy="true" />;

  const property = properties.find((p) => p.id === id);
  if (!property)
    return (
      <EmptyState
        title="Property not found"
        description="It may have been deleted, or it was saved in a different browser."
        action={<ButtonLink href="/properties">Back to saved properties</ButtonLink>}
      />
    );

  const analysis = analyses[id];
  const isRent = analysis?.mode === "rent";
  const tabs = TABS.filter(([key]) => !(isRent && key === "renovation"));

  return (
    <>
      <PageHeader
        eyebrow={
          <Link href="/properties" className="hover:text-text">
            ← Saved properties
          </Link>
        }
        title={
          <span className="flex flex-wrap items-center gap-2">
            {property.title} {property.isSample && <SampleTag />}
          </span>
        }
        description={`${LISTING_TYPE_LABELS[property.listingType]} · ${PROPERTY_TYPE_LABELS[property.propertyType]} · ${property.district}${property.address ? ` · ${property.address}` : ""}`}
        actions={
          <>
            <Button variant="secondary" onClick={() => runAnalysis(property)}>
              <IconSpark /> Re-run
            </Button>
            {analysis && (
              <Button variant="secondary" onClick={() => window.print()}>
                <IconDownload /> Download report
              </Button>
            )}
            {confirmDelete ? (
              <Button
                variant="danger"
                onClick={() => {
                  deleteProperty(property.id);
                  router.push("/properties");
                }}
              >
                Confirm delete
              </Button>
            ) : (
              <Button variant="ghost" onClick={() => setConfirmDelete(true)} aria-label="Delete property">
                <IconTrash />
              </Button>
            )}
          </>
        }
      />

      {!analysis ? (
        <EmptyState
          title="Not analysed yet"
          description="Run the analysis to see the valuation range and the rest of the report."
          action={<Button onClick={() => runAnalysis(property)}>Run analysis</Button>}
        />
      ) : (
        <>
          <SampleNotice className="mb-6" />
          <Summary property={property} a={analysis} />

          <nav
            className="no-print mt-8 mb-6 flex gap-1 overflow-x-auto border-b border-line"
            aria-label="Report sections"
          >
            {tabs.map(([key, label]) => (
              <button
                key={key}
                onClick={() => setTab(key)}
                aria-current={tab === key ? "page" : undefined}
                className={cn(
                  "-mb-px border-b-2 px-3 py-2.5 text-sm whitespace-nowrap transition-colors",
                  tab === key
                    ? "border-brand-600 font-medium text-text"
                    : "border-transparent text-muted hover:text-text",
                )}
              >
                {label}
              </button>
            ))}
          </nav>

          {/* All sections render when printing so the report is complete. */}
          <Panel show={tab === "overview"}>
            <Overview property={property} a={analysis} />
          </Panel>
          <Panel show={tab === "inspection"}>
            <Inspection property={property} a={analysis} />
          </Panel>
          <Panel show={tab === "costs"}>
            {isRent ? <TenancyCosts a={analysis} /> : <Costs a={analysis} />}
          </Panel>
          {!isRent && (
            <Panel show={tab === "renovation"}>
              <Renovation property={property} a={analysis} />
            </Panel>
          )}
          <Panel show={tab === "negotiation"}>
            <Negotiation property={property} a={analysis} />
          </Panel>

          <p className="mt-8 text-xs text-subtle">
            Generated {formatDate(analysis.generatedAt)} by the {analysis.engine} engine. Estimates are illustrative and
            are not a formal valuation, survey or financial advice.
          </p>
        </>
      )}
    </>
  );
}

function Panel({ show, children }: { show: boolean; children: ReactNode }) {
  return <section className={cn("space-y-6 print:mb-6 print:block", show ? "block" : "hidden")}>{children}</section>;
}

function Summary({ property, a }: { property: Property; a: AnalysisResult }) {
  const rent = a.mode === "rent";
  const perMonth = rent ? "/month" : "";
  const perSqm = (n: number) => (rent ? formatRentPerSqm(n) : formatPerSqm(n));
  return (
    <Card className="grid gap-6 p-6 lg:grid-cols-[1fr_auto]">
      <div>
        <div className="flex flex-wrap items-center gap-3">
          <p className="text-sm font-medium text-muted">{rent ? "Estimated market rent" : "Estimated value"}</p>
          <VerdictBadge verdict={a.verdict} deltaPct={a.deltaPct} />
        </div>
        <p className="tabular mt-1 text-3xl font-semibold tracking-tight">
          {formatEur(a.estimate.low)} – {formatEur(a.estimate.high)}
          {rent && <span className="text-lg font-medium text-muted">/month</span>}
        </p>
        <p className="mt-1 text-sm text-muted">
          Mid estimate{" "}
          <span className="tabular font-medium text-text">
            {formatEur(a.estimate.mid)}
            {perMonth}
          </span>{" "}
          versus asking <span className="tabular font-medium text-text">{formatAsking(property)}</span>
          {rent && a.propertyValue && (
            <>
              {" "}
              · estimated sale value{" "}
              <span className="tabular font-medium text-text">{formatEur(a.propertyValue)}</span>
            </>
          )}
        </p>
        <ValueRangeBar low={a.estimate.low} mid={a.estimate.mid} high={a.estimate.high} asking={property.askingPrice} />
        <div className="grid grid-cols-3 gap-4 border-t border-line pt-4">
          <Stat label={rent ? "Asking rent / m²" : "Asking / m²"} value={perSqm(a.pricePerSqm.asking)} />
          <Stat label="Estimate / m²" value={perSqm(a.pricePerSqm.estimated)} />
          <Stat label="Area ref. / m²" value={perSqm(a.pricePerSqm.district)} />
        </div>
      </div>
      <div className="flex flex-col items-center gap-4 border-line lg:w-60 lg:border-l lg:pl-6">
        <ScoreRing value={a.scores.overall} label="Score" />
        <div className="w-full space-y-3">
          <Meter label="Price" value={a.scores.price} />
          <Meter label="Location" value={a.scores.location} />
          <Meter label="Condition" value={a.scores.condition} />
          <Meter label="Energy" value={a.scores.energy} />
          <p className="pt-1 text-center text-xs text-muted">Confidence {a.confidence}%</p>
        </div>
      </div>
    </Card>
  );
}

function Overview({ property, a }: { property: Property; a: AnalysisResult }) {
  const rent = a.mode === "rent";
  const compRows = [
    { label: rent ? "This property (asking rent)" : "This property (asking)", value: a.pricePerSqm.asking, highlight: true },
    { label: "Estimate", value: a.pricePerSqm.estimated },
    ...a.comparables.map((c) => ({
      label: c.label,
      value: c.pricePerSqm,
      sublabel: `${c.distanceKm} km · built ${c.yearBuilt}`,
    })),
  ];
  return (
    <>
      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader
            title="What moves the value"
            description={`Adjustments to the ${property.district} reference ${rent ? "rent" : "price"}`}
          />
          <div className="px-5 py-2">
            {a.factors.length ? (
              <FactorBars factors={a.factors} />
            ) : (
              <p className="py-4 text-sm text-muted">No adjustments.</p>
            )}
          </div>
        </Card>
        <Card>
          <CardHeader
            title="Market comparator"
            description={rent ? "Monthly rent per m² against similar lettings" : "Price per m² against similar properties"}
            action={<Badge tone="gold">Generated</Badge>}
          />
          <div className="p-5">
            <PricePerSqmBars rows={compRows} format={rent ? formatRentPerSqm : formatPerSqm} />
            <p className="mt-4 text-xs text-subtle">
              Comparables are generated from the estimate for layout purposes, not real listings.
            </p>
          </div>
        </Card>
      </div>
      <Card>
        <CardHeader title="Property details" />
        <DetailList
          rows={[
            ["Listing", LISTING_TYPE_LABELS[property.listingType]],
            ["Asking", formatAsking(property)],
            ["Size", `${property.sizeSqm} m²`],
            ["Bedrooms / baths", `${property.bedrooms} / ${property.bathrooms}`],
            ["Floor", property.floor === 0 ? "Ground" : property.floor],
            ["Year built", property.yearBuilt],
            ["Renovated", property.renovatedYear ?? "—"],
            ["Construction", CONSTRUCTION_LABELS[property.constructionType]],
            ["Condition", CONDITION_LABELS[property.condition]],
            [
              "Energy",
              `${property.energyClass} · ${CERTIFICATE_LABELS[property.energyCertificate.status].toLowerCase()}${
                property.energyCertificate.number ? ` (${property.energyCertificate.number})` : ""
              }`,
            ],
            ["Heating", HEATING_LABELS[property.heating]],
            ["Cooling", COOLING_LABELS[property.cooling]],
            ["Balcony", property.balconySqm ? `${property.balconySqm} m²` : "None"],
            ["Elevator", property.elevator ? "Yes" : "No"],
            ["Parking", property.parking ? "Yes" : "No"],
            ["Storage", property.storage ? "Yes" : "No"],
            [
              "Location",
              property.location ? `${property.location.lat.toFixed(5)}, ${property.location.lon.toFixed(5)}` : "Not set",
            ],
          ]}
        />
        {property.notes && <p className="border-t border-line px-5 py-4 text-sm text-muted">{property.notes}</p>}
      </Card>
      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title="Furniture and appliances" description="Checklist from the submission" />
          <ul className="divide-y divide-line">
            {APPLIANCES.map((k) => (
              <ChecklistItem
                key={k}
                label={APPLIANCE_LABELS[k]}
                value={APPLIANCE_STATUS_LABELS[property.checklist.appliances[k]]}
                tone={APPLIANCE_TONE[property.checklist.appliances[k]]}
              />
            ))}
            <ChecklistItem
              label="Heating"
              value={HEATING_STATUS_LABELS[property.checklist.heating]}
              tone={HEATING_TONE[property.checklist.heating]}
            />
            <ChecklistItem
              label="Furniture"
              value={FURNITURE_STATUS_LABELS[property.checklist.furniture]}
              tone={property.checklist.furniture === "not-included" ? "neutral" : "positive"}
            />
          </ul>
          {property.checklist.otherItems && (
            <p className="border-t border-line px-5 py-3 text-sm text-muted">
              Also included: {property.checklist.otherItems}
            </p>
          )}
        </Card>
        <Card>
          <CardHeader title="Building and facilities" />
          <DetailList
            columns={2}
            rows={[
              [
                "Common charges",
                property.buildingExpenses.monthlyCommonCharges === null
                  ? "Not known"
                  : `${formatEur(property.buildingExpenses.monthlyCommonCharges)}/month`,
              ],
              ["Includes heating", property.buildingExpenses.includesHeating ? "Yes" : "No"],
              ["Planned works", property.buildingExpenses.plannedWorks || "None reported"],
            ]}
          />
          <div className="border-t border-line px-5 py-4">
            <p className="text-xs text-muted">Facilities</p>
            {property.facilities.length || property.otherFacilities ? (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {property.facilities.map((f) => (
                  <Badge key={f}>{FACILITY_LABELS[f]}</Badge>
                ))}
                {property.otherFacilities && <Badge>{property.otherFacilities}</Badge>}
              </div>
            ) : (
              <p className="mt-1 text-sm">None listed</p>
            )}
          </div>
        </Card>
      </div>
    </>
  );
}

type Tone = "neutral" | "positive" | "negative" | "warning";
const APPLIANCE_TONE: Record<ApplianceStatus, Tone> = {
  working: "positive",
  damaged: "negative",
  missing: "warning",
  unknown: "neutral",
};
const HEATING_TONE: Record<HeatingStatus, Tone> = { available: "positive", unavailable: "negative", unknown: "neutral" };

function ChecklistItem({ label, value, tone }: { label: string; value: string; tone: Tone }) {
  return (
    <li className="flex items-center justify-between gap-3 px-5 py-2.5 text-sm">
      <span>{label}</span>
      <Badge tone={tone}>{value}</Badge>
    </li>
  );
}

function DetailList({ rows, columns = 4 }: { rows: [string, ReactNode][]; columns?: 2 | 4 }) {
  return (
    <dl className={cn("grid gap-x-6 gap-y-4 p-5 text-sm sm:grid-cols-2", columns === 4 && "sm:grid-cols-3 lg:grid-cols-4")}>
      {rows.map(([k, v]) => (
        <div key={k}>
          <dt className="text-xs text-muted">{k}</dt>
          <dd className="mt-0.5 font-medium">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

const SEVERITY: Record<InspectionSeverity, { label: string; tone: "neutral" | "warning" | "negative" | "brand" }> = {
  info: { label: "Info", tone: "brand" },
  low: { label: "Low", tone: "neutral" },
  medium: { label: "Medium", tone: "warning" },
  high: { label: "High", tone: "negative" },
};

function Inspection({ property, a }: { property: Property; a: AnalysisResult }) {
  const total = a.inspection.reduce((s, f) => s + f.estimatedCost, 0);
  return (
    <>
      <Card>
        <CardHeader
          title="AI Home Inspector"
          description="Potential issues to check during a viewing or survey"
          action={<span className="tabular text-sm font-semibold">≈ {formatEur(total)}</span>}
        />
        <ul className="divide-y divide-line">
          {a.inspection.map((f, i) => (
            <li key={i} className="grid gap-2 px-5 py-4 sm:grid-cols-[8rem_1fr_auto] sm:items-start sm:gap-4">
              <div className="flex items-center gap-2 sm:flex-col sm:items-start">
                <span className="text-sm font-medium">{f.area}</span>
                <Badge tone={SEVERITY[f.severity].tone}>{SEVERITY[f.severity].label}</Badge>
              </div>
              <p className="text-sm text-muted">{f.finding}</p>
              <p className="tabular text-sm font-medium sm:text-right">
                {f.estimatedCost ? formatEur(f.estimatedCost) : "—"}
              </p>
            </li>
          ))}
        </ul>
      </Card>
      {property.photos.length > 0 && (
        <Card>
          <CardHeader title="Photographs" description="Queued for visual defect detection when the AI service ships" />
          <div className="space-y-5 p-5">
            {photosByArea(property.photos).map((g) => (
              <div key={g.area}>
                <p className="mb-2 text-xs font-medium text-muted">
                  {PHOTO_AREA_LABELS[g.area]} <span className="tabular">({g.photos.length})</span>
                </p>
                <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                  {g.photos.map((p) => (
                    <li key={p.id} className="aspect-[4/3] overflow-hidden rounded-lg ring-1 ring-line">
                      {/* eslint-disable-next-line @next/next/no-img-element -- local data URL */}
                      <img
                        src={p.dataUrl}
                        alt={`${PHOTO_AREA_LABELS[g.area]}: ${p.name}`}
                        className="h-full w-full object-cover"
                      />
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </Card>
      )}
    </>
  );
}

function Costs({ a }: { a: AnalysisResult }) {
  const f = a.financial;
  const purchaseCosts = f.totalAcquisition - (f.deposit + f.loanAmount);
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card>
        <CardHeader title="Financial calculator" description="Purchase costs and monthly ownership" />
        <div className="space-y-6 p-5">
          <div className="grid grid-cols-2 gap-4">
            <Stat
              label="Total to buy"
              value={formatEur(f.totalAcquisition)}
              hint={`incl. ${formatEur(purchaseCosts)} costs`}
            />
            <Stat label="Cash needed" value={formatEur(f.deposit + purchaseCosts)} hint="Deposit + costs" />
          </div>
          <StackedBar
            parts={[
              { label: "Deposit", amount: f.deposit },
              { label: "Mortgage", amount: f.loanAmount },
              ...f.acquisitionCosts,
            ]}
          />
          <div className="grid grid-cols-2 gap-4 border-t border-line pt-4">
            <Stat
              label="Mortgage / month"
              value={formatEur(f.monthlyMortgage)}
              hint={`${formatEur(f.loanAmount)} at ${formatPct(f.annualRate * 100)} over ${f.years} yrs`}
            />
            <Stat
              label="Ownership / month"
              value={formatEur(f.monthlyOwnership)}
              hint="Mortgage, common charges, tax, insurance"
            />
          </div>
          <p className="text-xs text-subtle">
            Mortgage assumptions can be changed in{" "}
            <Link href="/settings" className="underline">
              settings
            </Link>
            , then re-run the analysis.
          </p>
        </div>
      </Card>
      <Card>
        <CardHeader title="Investment calculator" description="If you rent the property out" />
        <div className="space-y-6 p-5">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="Rent / month" value={formatEur(a.rental.monthlyRent)} />
            <Stat label="Gross yield" value={formatPct(a.rental.grossYieldPct)} />
            <Stat
              label="Net yield"
              value={formatPct(a.rental.netYieldPct)}
              tone={a.rental.netYieldPct >= 4 ? "positive" : "negative"}
              hint={a.rental.netYieldPct >= 4 ? "Healthy" : "Thin"}
            />
          </div>
          <div className="rounded-lg bg-canvas p-4 text-sm">
            <div className="flex justify-between">
              <span className="text-muted">Monthly rent</span>
              <span className="tabular font-medium">{formatEur(a.rental.monthlyRent)}</span>
            </div>
            <div className="mt-2 flex justify-between">
              <span className="text-muted">Monthly ownership cost</span>
              <span className="tabular font-medium">−{formatEur(f.monthlyOwnership)}</span>
            </div>
            <div className="mt-2 flex justify-between border-t border-line pt-2 font-semibold">
              <span>Monthly cash flow (before tax)</span>
              <span
                className={cn(
                  "tabular",
                  a.rental.monthlyRent - f.monthlyOwnership >= 0 ? "text-positive" : "text-negative",
                )}
              >
                {formatEur(a.rental.monthlyRent - f.monthlyOwnership)}
              </span>
            </div>
          </div>
          <p className="text-xs text-subtle">Net yield assumes 28% of rent goes to tax, vacancy and maintenance.</p>
        </div>
      </Card>
    </div>
  );
}

function TenancyCosts({ a }: { a: AnalysisResult }) {
  const t = a.tenancy;
  const f = a.financial;
  if (!t) return null;
  const purchaseCosts = f.totalAcquisition - (f.deposit + f.loanAmount);
  const utilities = t.monthlyBreakdown.find((x) => x.label.startsWith("Utilities"))?.amount ?? 0;
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card>
        <CardHeader title="Renting it" description="Move-in costs and monthly living costs" />
        <div className="space-y-6 p-5">
          <div className="grid grid-cols-2 gap-4">
            <Stat label="To move in" value={formatEur(t.totalMoveIn)} hint="Rent, deposit, fees, items to buy" />
            <Stat label="Per month" value={formatEur(t.monthlyCost)} hint="Rent, common charges, utilities" />
          </div>
          <StackedBar parts={t.moveInCosts} />
          <div className="rounded-lg bg-canvas p-4 text-sm">
            {t.monthlyBreakdown.map((x) => (
              <div key={x.label} className="mt-2 flex justify-between first:mt-0">
                <span className="text-muted">{x.label}</span>
                <span className="tabular font-medium">{formatEur(x.amount)}</span>
              </div>
            ))}
            <div className="mt-2 flex justify-between border-t border-line pt-2 font-semibold">
              <span>Total per month</span>
              <span className="tabular">{formatEur(t.monthlyCost)}</span>
            </div>
          </div>
          <p className="text-xs text-subtle">
            The deposit is returned at the end of the lease. Utilities are a size-based estimate.
          </p>
        </div>
      </Card>
      <Card>
        <CardHeader title="Buying instead" description={`At the estimated value of ${formatEur(a.propertyValue ?? 0)}`} />
        <div className="space-y-6 p-5">
          <div className="grid grid-cols-2 gap-4">
            <Stat label="Cash needed" value={formatEur(f.deposit + purchaseCosts)} hint="Deposit + purchase costs" />
            <Stat
              label="Ownership / month"
              value={formatEur(f.monthlyOwnership)}
              hint={`${formatEur(f.loanAmount)} at ${formatPct(f.annualRate * 100)} over ${f.years} yrs`}
            />
          </div>
          <div className="rounded-lg bg-canvas p-4 text-sm">
            <div className="flex justify-between">
              <span className="text-muted">Renting, per month</span>
              <span className="tabular font-medium">{formatEur(t.monthlyCost)}</span>
            </div>
            <div className="mt-2 flex justify-between">
              <span className="text-muted">Owning, per month (with utilities)</span>
              <span className="tabular font-medium">{formatEur(f.monthlyOwnership + utilities)}</span>
            </div>
          </div>
          <p className="text-xs text-subtle">
            Owning builds equity but ties up cash. Mortgage assumptions can be changed in{" "}
            <Link href="/settings" className="underline">
              settings
            </Link>
            .
          </p>
        </div>
      </Card>
    </div>
  );
}

function Renovation({ property, a }: { property: Property; a: AnalysisResult }) {
  return (
    <div className="grid gap-4 md:grid-cols-3">
      {a.renovation.map((s) => {
        const totalIn = property.askingPrice + s.cost;
        const equity = s.valueAfter - totalIn;
        return (
          <Card key={s.tier} className={cn("flex flex-col p-5", s.tier === "medium" && "ring-2 ring-brand-500")}>
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold">{s.label}</h3>
              {s.tier === "medium" && <Badge tone="brand">Typical</Badge>}
            </div>
            <p className="tabular mt-3 text-2xl font-semibold">{formatEur(s.cost)}</p>
            <p className="tabular text-xs text-muted">{formatPerSqm(Math.round(s.cost / property.sizeSqm))}</p>
            <ul className="mt-4 space-y-1.5 text-sm">
              {s.scope.map((item) => (
                <li key={item} className="flex gap-2">
                  <IconCheck className="mt-0.5 shrink-0 text-brand-600" width={16} height={16} />
                  {item}
                </li>
              ))}
            </ul>
            <div className="mt-auto space-y-1.5 border-t border-line pt-4 text-sm">
              <div className="mt-4 flex justify-between">
                <span className="text-muted">Value after works</span>
                <span className="tabular font-medium">{formatEur(s.valueAfter)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted">Asking + works</span>
                <span className="tabular font-medium">{formatEur(totalIn)}</span>
              </div>
              <div className="flex justify-between font-semibold">
                <span>Equity created</span>
                <span className={cn("tabular", equity >= 0 ? "text-positive" : "text-negative")}>
                  {formatEur(equity)}
                </span>
              </div>
            </div>
          </Card>
        );
      })}
    </div>
  );
}

function Negotiation({ property, a }: { property: Property; a: AnalysisResult }) {
  const n = a.negotiation;
  const rent = a.mode === "rent";
  const money = (v: number) => (rent ? `${formatEur(v)}/month` : formatEur(v));
  const steps = [
    { label: "Opening offer", value: n.openingOffer, hint: "Start here" },
    { label: rent ? "Target rent" : "Target price", value: n.targetPrice, hint: "Where you aim to settle" },
    { label: "Walk-away maximum", value: n.maxOffer, hint: "Do not exceed" },
    {
      label: rent ? "Asking rent" : "Asking price",
      value: property.askingPrice,
      hint: rent ? "Landlord's position" : "Seller's position",
    },
  ];
  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_1.2fr]">
      <Card>
        <CardHeader title="Negotiation assistant" description={rent ? "Suggested rent offers" : "Suggested offer range"} />
        <ol className="divide-y divide-line">
          {steps.map((s, i) => (
            <li key={s.label} className="flex items-center gap-4 px-5 py-4">
              <span
                className={cn(
                  "tabular flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold",
                  i === 3 ? "bg-canvas text-muted ring-1 ring-line" : "bg-ink-900 text-white",
                )}
              >
                {i + 1}
              </span>
              <div className="flex-1">
                <p className="text-sm font-medium">{s.label}</p>
                <p className="text-xs text-muted">{s.hint}</p>
              </div>
              <div className="text-right">
                <p className="tabular font-semibold">{money(s.value)}</p>
                <p className="tabular text-xs text-muted">
                  {formatPct(((s.value - property.askingPrice) / property.askingPrice) * 100, true)} vs asking
                </p>
              </div>
            </li>
          ))}
        </ol>
      </Card>
      <Card>
        <CardHeader title="Arguments you can use" description="Evidence behind the offer" />
        <ul className="space-y-3 p-5">
          {n.arguments.map((arg) => (
            <li key={arg} className="flex gap-3 text-sm">
              <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-500" />
              {arg}
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
