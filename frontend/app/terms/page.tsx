import type { Metadata } from "next";
import Link from "next/link";
import { LegalPage, Section } from "@/components/legal-page";

export const metadata: Metadata = { title: "Terms of use" };

export default function TermsPage() {
  return (
    <LegalPage
      title="Terms of use"
      intro={
        <p>
          Property Intelligence gives you a second opinion before you make an offer on a property. These terms explain
          what the results are, and what they are not.
        </p>
      }
    >
      <Section id="estimates" title="Valuations are automated estimates, not professional appraisals">
        <p>
          The valuation range, verdict, scores and offers are calculated by software from comparable market records and
          the details you enter. No one has inspected the property. When the evidence is too thin, the platform shows no
          figure.
        </p>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="rounded-lg bg-canvas p-4 ring-1 ring-line">
            <p className="font-semibold">Automated estimate (this platform)</p>
            <ul className="mt-2 list-disc space-y-1 pl-5 text-muted">
              <li>Software, from records and your inputs</li>
              <li>No site visit, no legal or technical checks</li>
              <li>A range, with the evidence and its quality shown</li>
              <li>For your own decision before an offer</li>
            </ul>
          </div>
          <div className="rounded-lg bg-canvas p-4 ring-1 ring-line">
            <p className="font-semibold">Professional appraisal</p>
            <ul className="mt-2 list-disc space-y-1 pl-5 text-muted">
              <li>A certified valuer, who is accountable for it</li>
              <li>Visits the property, checks its legal and technical status</li>
              <li>A signed report under valuation standards</li>
              <li>Accepted by banks, courts and tax authorities</li>
            </ul>
          </div>
        </div>
        <p>
          Do not use an estimate as the basis for a mortgage, a court or tax filing, or as financial advice. Ask a
          certified valuer for that.
        </p>
      </Section>

      <Section id="documents" title="The Document Checker is an assistance tool, not a legal certification">
        <p>
          It compares the details you enter from each document (deed, cadastre, energy certificate, engineer&apos;s
          certificate, permits) with common requirements and with each other, and lists what looks missing, expired or
          inconsistent. It does not see or authenticate the documents. It does not search the land registry or the
          cadastre, and it does not certify that a title is clear or that a property can be sold or let. A lawyer&apos;s
          title search and the notary&apos;s checks are still needed before you sign.
        </p>
      </Section>

      <Section id="ai" title="AI photo and floor plan analysis">
        <p>
          The AI Home Inspector reports what is visible in your photos. Hidden damage, electrical safety and how far damp
          has spread cannot be established from photos. Those findings are marked as potential concerns, to be checked on
          site by a qualified professional. The Floor Plan Reader copies only the measurements printed on the plan. Both
          need your consent to send images to the AI provider (see the{" "}
          <Link href="/privacy#processors" className="font-medium text-brand-700 hover:underline">
            privacy notice
          </Link>
          ).
        </p>
      </Section>

      <Section id="plans" title="Plans, analyses and payments">
        <p>
          Plans and prices are on the{" "}
          <Link href="/pricing" className="font-medium text-brand-700 hover:underline">
            pricing page
          </Link>
          . They are initial prices and may change, with notice before your next billing period. One analysis is one
          property analysed in a calendar month (UTC): re-running the same property that month is free, and a failed
          analysis is not counted. Paid plans renew monthly through Stripe until you cancel in the billing portal. They
          stay active until the end of the period you paid for.
        </p>
      </Section>

      <Section id="use" title="Acceptable use">
        <ul className="list-disc space-y-1.5 pl-5">
          <li>Upload only photos and documents you have the right to share. Do not upload photos of people.</li>
          <li>Do not try to get around plan limits, access other accounts&apos; data or probe the service&apos;s security.</li>
          <li>API keys (Business) are secrets. Keep them out of public code and revoke them if they leak.</li>
        </ul>
      </Section>

      <Section id="liability" title="Liability">
        <p>
          Results are for information, to help you decide what to check and what to ask. You remain responsible for your
          decision to buy, rent or invest. Nothing in these terms limits rights you have as a consumer under the law.
        </p>
      </Section>
    </LegalPage>
  );
}
