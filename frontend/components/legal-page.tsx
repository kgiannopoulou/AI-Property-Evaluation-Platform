import type { ReactNode } from "react";
import { POLICY_VERSION } from "@/lib/legal";
import { SiteFooter, SiteHeader } from "./site-chrome";

/** Layout for the privacy notice and the terms. */
export function LegalPage({ title, intro, children }: { title: string; intro: ReactNode; children: ReactNode }) {
  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-3xl px-4 py-14 sm:px-6">
        <p className="text-sm font-semibold text-brand-600">Version {POLICY_VERSION}</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">{title}</h1>
        <div className="mt-4 text-muted">{intro}</div>
        <p className="mt-6 rounded-lg border border-warning/30 bg-warning-soft px-4 py-3 text-sm">
          Draft for a portfolio project. Before a public launch, the operator&apos;s legal name, address and contact
          details must be filled in, and the text reviewed by a lawyer.
        </p>
        <div className="legal mt-10 space-y-10">{children}</div>
      </main>
      <SiteFooter />
    </>
  );
}

export function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-title`} className="scroll-mt-20">
      <h2 id={`${id}-title`} className="text-xl font-semibold tracking-tight">
        {title}
      </h2>
      <div className="mt-3 space-y-3 text-sm leading-relaxed text-text/90">{children}</div>
    </section>
  );
}

export function Table({ head, rows }: { head: string[]; rows: ReactNode[][] }) {
  return (
    <div className="relative overflow-x-auto rounded-lg ring-1 ring-line">
      <table className="w-full min-w-[32rem] text-left text-sm">
        <thead className="bg-canvas text-xs text-muted">
          <tr>
            {head.map((h) => (
              <th key={h} scope="col" className="px-3 py-2 font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {rows.map((r, i) => (
            <tr key={i}>
              {r.map((c, j) => (
                <td key={j} className="px-3 py-2 align-top">
                  {c}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
