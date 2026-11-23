import { cn, formatEurCompact, formatPerSqm } from "@/lib/format";

/**
 * Lightweight SVG/CSS charts. Kept dependency-free on purpose; a chart
 * library can replace these once real data volumes justify it.
 */

/** Estimate band (low–high) with the mid estimate and the asking price marked. */
export function ValueRangeBar({
  low,
  mid,
  high,
  asking,
}: {
  low: number;
  mid: number;
  high: number;
  asking: number;
}) {
  const min = Math.min(low, asking) * 0.94;
  const max = Math.max(high, asking) * 1.06;
  const pos = (v: number) => ((v - min) / (max - min)) * 100;
  const askingAbove = asking > high;
  const askingBelow = asking < low;
  return (
    <div className="pt-7 pb-8">
      <div className="relative h-3 rounded-full bg-canvas ring-1 ring-line">
        <div
          className="absolute inset-y-0 rounded-full bg-brand-100 ring-1 ring-brand-500/30"
          style={{ left: `${pos(low)}%`, width: `${pos(high) - pos(low)}%` }}
        />
        <Marker at={pos(mid)} label={`Estimate ${formatEurCompact(mid)}`} tone="brand" placement="top" />
        <Marker
          at={pos(asking)}
          label={`Asking ${formatEurCompact(asking)}`}
          tone={askingAbove ? "negative" : askingBelow ? "positive" : "ink"}
          placement="bottom"
        />
      </div>
      <div className="tabular mt-9 flex justify-between text-xs text-muted">
        <span>Low {formatEurCompact(low)}</span>
        <span>High {formatEurCompact(high)}</span>
      </div>
    </div>
  );
}

function Marker({
  at,
  label,
  tone,
  placement,
}: {
  at: number;
  label: string;
  tone: "brand" | "negative" | "positive" | "ink";
  placement: "top" | "bottom";
}) {
  const color = {
    brand: "bg-brand-600 text-brand-700",
    negative: "bg-negative text-negative",
    positive: "bg-positive text-positive",
    ink: "bg-ink-900 text-ink-900",
  }[tone];
  const [dot, text] = color.split(" ");
  const clampedAt = Math.min(96, Math.max(4, at));
  return (
    <div className="absolute top-1/2" style={{ left: `${at}%` }}>
      <div className={cn("absolute h-5 w-1 -translate-x-1/2 -translate-y-1/2 rounded-full ring-2 ring-white", dot)} />
      <div
        className={cn(
          "tabular absolute text-xs font-semibold whitespace-nowrap",
          text,
          placement === "top" ? "-top-7" : "top-3.5",
        )}
        style={{ transform: `translateX(-${clampedAt}%)` }}
      >
        {label}
      </div>
    </div>
  );
}

/** Horizontal bars of price per m², with the subject property highlighted. */
export function PricePerSqmBars({
  rows,
  format = formatPerSqm,
}: {
  rows: { label: string; value: number; highlight?: boolean; sublabel?: string }[];
  /** Defaults to sale prices per m²; rentals pass a per-month formatter. */
  format?: (n: number) => string;
}) {
  const max = Math.max(...rows.map((r) => r.value)) * 1.08;
  return (
    <ul className="space-y-2.5">
      {rows.map((r) => (
        <li key={r.label} className="grid grid-cols-[minmax(0,10rem)_1fr_auto] items-center gap-3 text-sm">
          <span className={cn("truncate", r.highlight ? "font-semibold text-text" : "text-muted")} title={r.label}>
            {r.label}
            {r.sublabel && <span className="block truncate text-xs text-subtle">{r.sublabel}</span>}
          </span>
          <span className="h-2.5 rounded-full bg-canvas">
            <span
              className={cn("block h-full rounded-full", r.highlight ? "bg-ink-900" : "bg-brand-500/70")}
              style={{ width: `${(r.value / max) * 100}%` }}
            />
          </span>
          <span className={cn("tabular w-24 text-right", r.highlight ? "font-semibold" : "text-muted")}>
            {format(r.value)}
          </span>
        </li>
      ))}
    </ul>
  );
}

/** Diverging bars for valuation adjustments (negative left, positive right). */
export function FactorBars({ factors }: { factors: { label: string; impactPct: number; detail: string }[] }) {
  const maxAbs = Math.max(8, ...factors.map((f) => Math.abs(f.impactPct)));
  return (
    <ul className="divide-y divide-line">
      {factors.map((f) => {
        const width = (Math.abs(f.impactPct) / maxAbs) * 50;
        const positive = f.impactPct > 0;
        return (
          <li key={f.label} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)_3.5rem] items-center gap-3 py-2.5">
            <div className="min-w-0">
              <p className="text-sm font-medium text-text">{f.label}</p>
              <p className="truncate text-xs text-muted" title={f.detail}>
                {f.detail}
              </p>
            </div>
            <div className="relative h-2.5 rounded-full bg-canvas">
              <span className="absolute inset-y-[-3px] left-1/2 w-px bg-line-strong" />
              <span
                className={cn("absolute inset-y-0 rounded-full", positive ? "bg-positive/80" : "bg-negative/80")}
                style={positive ? { left: "50%", width: `${width}%` } : { right: "50%", width: `${width}%` }}
              />
            </div>
            <span className={cn("tabular text-right text-sm font-semibold", positive ? "text-positive" : "text-negative")}>
              {positive ? "+" : ""}
              {f.impactPct}%
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** Circular 0–100 score. */
export function ScoreRing({ value, size = 112, label }: { value: number; size?: number; label?: string }) {
  const stroke = 9;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const color = value >= 70 ? "#15803d" : value >= 50 ? "#b98a2e" : "#c2410c";
  return (
    <div className="relative inline-flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90" aria-hidden>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#e2e7ee" strokeWidth={stroke} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke={color}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={`${(value / 100) * c} ${c}`}
        />
      </svg>
      <div className="absolute text-center">
        <span className="tabular block text-2xl font-semibold text-text">{value}</span>
        {label && <span className="block text-[11px] font-medium tracking-wide text-muted uppercase">{label}</span>}
      </div>
    </div>
  );
}

/** Thin labelled meter used for score breakdowns and plan usage. */
export function Meter({ label, value, max = 100, suffix }: { label: string; value: number; max?: number; suffix?: string }) {
  const pct = Math.min(100, (value / max) * 100);
  const color = max === 100 ? (value >= 70 ? "bg-positive" : value >= 50 ? "bg-gold-500" : "bg-negative") : "bg-brand-600";
  return (
    <div>
      <div className="flex justify-between text-sm">
        <span className="text-muted">{label}</span>
        <span className="tabular font-medium text-text">
          {value}
          {suffix ?? (max === 100 ? "" : ` / ${max}`)}
        </span>
      </div>
      <div className="mt-1.5 h-1.5 rounded-full bg-canvas">
        <div className={cn("h-full rounded-full", color)} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

/** Stacked horizontal bar showing how a total splits into parts. */
export function StackedBar({ parts }: { parts: { label: string; amount: number }[] }) {
  const total = parts.reduce((s, p) => s + p.amount, 0);
  const palette = ["bg-ink-900", "bg-brand-600", "bg-brand-500/60", "bg-gold-500", "bg-ink-600/50", "bg-line-strong"];
  return (
    <div>
      <div className="flex h-3 overflow-hidden rounded-full">
        {parts.map((p, i) => (
          <div key={p.label} className={palette[i % palette.length]} style={{ width: `${(p.amount / total) * 100}%` }} />
        ))}
      </div>
      <ul className="mt-3 grid grid-cols-1 gap-1.5 text-sm sm:grid-cols-2">
        {parts.map((p, i) => (
          <li key={p.label} className="flex items-center gap-2">
            <span className={cn("h-2.5 w-2.5 shrink-0 rounded-sm", palette[i % palette.length])} />
            <span className="truncate text-muted">{p.label}</span>
            <span className="tabular ml-auto font-medium">{formatEurCompact(p.amount)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
