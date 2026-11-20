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
      "No. Reports are independent estimates to support your decision and negotiation. Banks and courts require a certified valuer.",
    ],
    [
      "Can I cancel at any time?",
      "Yes. Paid plans are monthly or annual and can be cancelled from your settings; you keep access until the end of the period.",
    ],
    [
      "Is payment live in this version?",
      "Not yet. Subscriptions will be processed by Stripe (or a local provider) through the backend. Payment keys never reach the browser.",
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
