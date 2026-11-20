const eur = new Intl.NumberFormat("en-GB", { style: "currency", currency: "EUR", maximumFractionDigits: 0 });
const num = new Intl.NumberFormat("en-GB", { maximumFractionDigits: 0 });
const date = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric" });

export const formatEur = (n: number) => eur.format(n);
export const formatNumber = (n: number) => num.format(n);
export const formatDate = (iso: string) => date.format(new Date(iso));
export const formatPerSqm = (n: number) => `${eur.format(n)}/m²`;
export const formatPct = (n: number, signed = false) => `${signed && n > 0 ? "+" : ""}${n.toFixed(1)}%`;

/** Compact euros for chart labels: €285k, €1.2m. */
export function formatEurCompact(n: number): string {
  if (Math.abs(n) >= 1_000_000) return `€${(n / 1_000_000).toFixed(n % 1_000_000 === 0 ? 0 : 1)}m`;
  if (Math.abs(n) >= 1_000) return `€${Math.round(n / 1_000)}k`;
  return `€${Math.round(n)}`;
}

export function cn(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(" ");
}
