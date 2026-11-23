import type { Verdict } from "@/analysis/types";
import { IconHome } from "@/components/icons";
import { Badge } from "@/components/ui";
import { cn, formatEur } from "@/lib/format";
import type { Property } from "./types";

export const VERDICT_LABELS: Record<Verdict, string> = {
  "below-market": "Below estimate",
  fair: "Fairly priced",
  "above-market": "Above estimate",
};

export function VerdictBadge({ verdict, deltaPct }: { verdict: Verdict; deltaPct?: number }) {
  const tone = verdict === "below-market" ? "positive" : verdict === "above-market" ? "negative" : "brand";
  return (
    <Badge tone={tone}>
      {VERDICT_LABELS[verdict]}
      {deltaPct !== undefined && (
        <span className="tabular font-semibold">
          {deltaPct > 0 ? "+" : ""}
          {deltaPct}%
        </span>
      )}
    </Badge>
  );
}

export function SampleTag() {
  return (
    <Badge tone="gold" title="Demo record seeded for this preview">
      Sample
    </Badge>
  );
}

/** First photo of a property, or a neutral placeholder. */
export function PropertyThumb({ property, className }: { property: Property; className?: string }) {
  const photo = property.photos[0];
  return (
    <div className={cn("relative overflow-hidden rounded-lg bg-canvas ring-1 ring-line", className)}>
      {photo ? (
        // eslint-disable-next-line @next/next/no-img-element -- local data URL preview
        <img src={photo.dataUrl} alt="" className="h-full w-full object-cover" />
      ) : (
        <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-ink-800 to-ink-600 text-white/40">
          <IconHome width={28} height={28} />
        </div>
      )}
    </div>
  );
}

/** Asking price, or asking rent with "/month" for rentals. */
export function formatAsking(p: Pick<Property, "askingPrice" | "listingType">): string {
  return p.listingType === "rent" ? `${formatEur(p.askingPrice)}/month` : formatEur(p.askingPrice);
}
