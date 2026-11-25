import type { Metadata } from "next";
import { SiteFooter, SiteHeader } from "@/components/site-chrome";
import { PricingPlans } from "./pricing-plans";

export const metadata: Metadata = { title: "Pricing" };

export default function PricingPage() {
  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
        <div className="mx-auto max-w-2xl text-center">
          <p className="text-sm font-semibold text-brand-600">Pricing</p>
          <h1 className="mt-2 text-4xl font-semibold tracking-tight">A plan for every stage of the search.</h1>
          <p className="mt-3 text-muted">
            Start free. Upgrade when you are comparing more homes, or evaluating properties for clients.
          </p>
        </div>
        <PricingPlans />
        <Faq />
      </main>
      <SiteFooter />
    </>
  );
}

function Faq() {
  const items = [
    [
      "Is this a formal valuation?",
      "No. Valuations are automated estimates to support your decision and negotiation, not professional appraisals. Banks, courts and tax authorities require a certified valuer.",
    ],
    [
      "Does the Document Checker confirm the title is clear?",
      "No. It is an assistance tool that flags missing, expired or inconsistent documents from the details you enter. It is not a legal certification; a lawyer's title search and the notary's checks are still needed.",
    ],
    [
      "Can I cancel at any time?",
      "Yes. Paid plans are monthly and are cancelled in the billing portal from your settings; you keep access until the end of the period.",
    ],
    [
      "What counts as an analysis?",
      "Analysing one property in a calendar month: its valuation, AI inspection, negotiation, floor plan or report. Running them again for the same property that month is free.",
    ],
    [
      "What happens when I reach my limit?",
      "The platform tells you how many analyses you have used and when the allowance resets on the 1st of the month. Your saved properties and earlier results stay available.",
    ],
    [
      "How are payments handled?",
      "Subscriptions are processed by Stripe. Card details go straight to Stripe and never reach our servers.",
    ],
  ];
  return (
    <section className="mx-auto mt-20 max-w-3xl">
      <h2 className="text-xl font-semibold">Questions</h2>
      <dl className="mt-4 divide-y divide-line border-y border-line">
        {items.map(([q, a]) => (
          <div key={q} className="py-5">
            <dt className="font-medium">{q}</dt>
            <dd className="mt-1.5 text-sm leading-relaxed text-muted">{a}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
