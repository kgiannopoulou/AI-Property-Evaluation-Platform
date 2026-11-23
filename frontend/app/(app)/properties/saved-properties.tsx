"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { IconCompare, IconPlus, IconTrash } from "@/components/icons";
import { Button, ButtonLink, Card, EmptyState, PageHeader } from "@/components/ui";
import { cn, formatDate, formatEurCompact, formatPerSqm, formatRentPerSqm } from "@/lib/format";
import { useHydrated } from "@/lib/local-store";
import { deleteProperty, useAnalyses, useProperties } from "@/properties/store";
import { PROPERTY_TYPE_LABELS } from "@/properties/types";
import { PropertyThumb, SampleTag, VerdictBadge, formatAsking } from "@/properties/ui";

type Sort = "newest" | "price" | "score" | "delta";
const SORTS: [Sort, string][] = [
  ["newest", "Newest"],
  ["price", "Asking price"],
  ["score", "Score"],
  ["delta", "Best value"],
];
const MAX_COMPARE = 4;

export function SavedProperties() {
  const hydrated = useHydrated();
  const router = useRouter();
  const properties = useProperties();
  const analyses = useAnalyses();
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<Sort>("newest");
  const [selected, setSelected] = useState<string[]>([]);
  const [confirming, setConfirming] = useState<string | null>(null);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    const filtered = properties.filter(
      (p) => !q || p.title.toLowerCase().includes(q) || p.district.toLowerCase().includes(q),
    );
    const score = (id: string) => analyses[id]?.scores.overall ?? -1;
    const delta = (id: string) => analyses[id]?.deltaPct ?? Infinity;
    return [...filtered].sort((a, b) => {
      if (sort === "price") return b.askingPrice - a.askingPrice;
      if (sort === "score") return score(b.id) - score(a.id);
      if (sort === "delta") return delta(a.id) - delta(b.id);
      return b.createdAt.localeCompare(a.createdAt);
    });
  }, [properties, analyses, query, sort]);

  const toggle = (id: string) =>
    setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : s.length < MAX_COMPARE ? [...s, id] : s));

  if (!hydrated) return <div className="h-96 animate-pulse rounded-xl bg-line/60" aria-busy="true" />;

  return (
    <>
      <PageHeader
        title="Saved properties"
        description="Every property you have analysed. Select up to four to compare side by side."
        actions={
          <>
            <Button
              variant="secondary"
              disabled={selected.length < 2}
              onClick={() => router.push(`/compare?ids=${selected.join(",")}`)}
            >
              <IconCompare /> Compare {selected.length ? `(${selected.length})` : ""}
            </Button>
            <ButtonLink href="/properties/new">
              <IconPlus /> Add property
            </ButtonLink>
          </>
        }
      />

      {properties.length === 0 ? (
        <EmptyState
          title="No saved properties"
          description="Analyse your first property to see it here."
          action={<ButtonLink href="/properties/new">Analyze a property</ButtonLink>}
        />
      ) : (
        <>
          <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <input
              type="search"
              className="input sm:max-w-xs"
              placeholder="Search by name or area"
              aria-label="Search saved properties"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <div className="flex gap-1 rounded-lg border border-line bg-surface p-1" role="group" aria-label="Sort by">
              {SORTS.map(([id, label]) => (
                <button
                  key={id}
                  onClick={() => setSort(id)}
                  aria-pressed={sort === id}
                  className={cn(
                    "rounded-md px-2.5 py-1 text-sm transition-colors",
                    sort === id ? "bg-ink-900 font-medium text-white" : "text-muted hover:text-text",
                  )}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>

          {rows.length === 0 ? (
            <p className="py-10 text-center text-sm text-muted">No properties match “{query}”.</p>
          ) : (
            <ul className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
              {rows.map((p) => {
                const a = analyses[p.id];
                const isSelected = selected.includes(p.id);
                return (
                  <li key={p.id}>
                    <Card className={cn("flex h-full flex-col overflow-hidden", isSelected && "ring-2 ring-brand-500")}>
                      <Link href={`/properties/${p.id}`} className="block">
                        <PropertyThumb property={p} className="aspect-[16/9] rounded-none ring-0" />
                      </Link>
                      <div className="flex flex-1 flex-col p-4">
                        <div className="flex items-start justify-between gap-2">
                          <Link href={`/properties/${p.id}`} className="min-w-0 hover:underline">
                            <h2 className="truncate text-sm font-semibold">{p.title}</h2>
                          </Link>
                          {p.isSample && <SampleTag />}
                        </div>
                        <p className="mt-0.5 text-xs text-muted">
                          {PROPERTY_TYPE_LABELS[p.propertyType]} · {p.district} · {p.sizeSqm} m² · {p.bedrooms} bed
                        </p>
                        <div className="mt-4 mb-4 flex items-end justify-between gap-2">
                          <div>
                            <p className="tabular text-lg font-semibold">{formatAsking(p)}</p>
                            <p className="tabular text-xs text-muted">
                              {p.listingType === "rent"
                                ? formatRentPerSqm(p.askingPrice / p.sizeSqm)
                                : formatPerSqm(Math.round(p.askingPrice / p.sizeSqm))}
                              {a && ` · est. ${formatEurCompact(a.estimate.mid)}`}
                            </p>
                          </div>
                          {a ? <VerdictBadge verdict={a.verdict} deltaPct={a.deltaPct} /> : null}
                        </div>
                        <div className="mt-auto flex items-center justify-between gap-2 border-t border-line pt-3">
                          <label className="flex items-center gap-2 text-sm text-muted">
                            <input
                              type="checkbox"
                              className="accent-brand-600"
                              checked={isSelected}
                              disabled={!isSelected && selected.length >= MAX_COMPARE}
                              onChange={() => toggle(p.id)}
                            />
                            Compare
                          </label>
                          <span className="text-xs text-subtle">Added {formatDate(p.createdAt)}</span>
                          {confirming === p.id ? (
                            <span className="flex gap-1">
                              <Button
                                size="sm"
                                variant="danger"
                                onClick={() => {
                                  deleteProperty(p.id);
                                  setSelected((s) => s.filter((x) => x !== p.id));
                                  setConfirming(null);
                                }}
                              >
                                Delete
                              </Button>
                              <Button size="sm" variant="ghost" onClick={() => setConfirming(null)}>
                                Keep
                              </Button>
                            </span>
                          ) : (
                            <button
                              className="text-subtle hover:text-negative"
                              onClick={() => setConfirming(p.id)}
                              aria-label={`Delete ${p.title}`}
                              title="Delete"
                            >
                              <IconTrash />
                            </button>
                          )}
                        </div>
                      </div>
                    </Card>
                  </li>
                );
              })}
            </ul>
          )}
        </>
      )}
    </>
  );
}
