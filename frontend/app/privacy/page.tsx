import type { Metadata } from "next";
import Link from "next/link";
import { LegalPage, Section, Table } from "@/components/legal-page";
import { PROCESSORS, RETENTION } from "@/lib/legal";

export const metadata: Metadata = { title: "Privacy notice" };

export default function PrivacyPage() {
  return (
    <LegalPage
      title="Privacy notice"
      intro={
        <p>
          What personal data the platform keeps, why, for how long, who else processes it, and how to use your rights
          under the GDPR. You can download or delete your data at any time in{" "}
          <Link href="/settings#privacy" className="font-medium text-brand-700 hover:underline">
            Settings → Privacy
          </Link>
          .
        </p>
      }
    >
      <Section id="controller" title="Who is responsible">
        <p>
          The controller is the operator of Property Intelligence. Contact: the privacy address published with the
          operator&apos;s details at launch. There is no data protection officer at this stage.
        </p>
      </Section>

      <Section id="data" title="What we keep and why">
        <Table
          head={["Data", "Purpose", "Legal basis (GDPR art. 6)"]}
          rows={[
            ["Name, email, role, password hash", "Your account and sign-in", "Contract (b)"],
            ["Properties, photos, floor plans and the details you enter", "Running the analyses you ask for", "Contract (b)"],
            [
              "Photos and floor plans sent to the AI provider",
              "AI Home Inspector and Floor Plan Reader",
              "Consent (a): asked before the first upload, can be withdrawn",
            ],
            ["Plan, payment status, usage counts", "Subscriptions and plan limits", "Contract (b); tax law for invoices (c)"],
            [
              "Audit log (sign-ins, data exports, file access, changes); IP addresses only as salted hashes",
              "Security of the service and of your account",
              "Legitimate interest (f)",
            ],
            ["Product news emails", "Telling you about new features", "Consent (a), off by default"],
          ]}
        />
        <p>
          Payment card details are entered on Stripe&apos;s pages and never reach our servers. In this version, saved
          properties are stored in your browser (localStorage), not on our servers.
        </p>
      </Section>

      <Section id="automated" title="Automated estimates">
        <p>
          Valuations, scores and negotiation ranges are produced automatically from comparable records and the details
          you enter. They are <strong>automated estimates, not professional appraisals</strong>, and they make no decision
          about you. Article 22 GDPR (decisions with legal or similarly significant effects) does not apply. See the{" "}
          <Link href="/terms#estimates" className="font-medium text-brand-700 hover:underline">
            terms
          </Link>{" "}
          for what an estimate is and is not.
        </p>
      </Section>

      <Section id="processors" title="Who else processes your data">
        <Table head={["Processor", "Purpose", "Data", "When"]} rows={PROCESSORS.map((p) => [p.name, p.purpose, p.data, p.when])} />
        <p>
          Each processor works under a data processing agreement. Some of them are based in the United States. Transfers
          outside the EEA rely on the provider&apos;s Standard Contractual Clauses or EU–US Data Privacy Framework
          certification, which is confirmed in the agreement before launch. We do not sell personal data or use it for
          advertising.
        </p>
      </Section>

      <Section id="retention" title="How long we keep it">
        <Table head={["Data", "Kept for"]} rows={RETENTION.map((r) => [r.data, r.kept])} />
        <p>An automatic job deletes expired data every night.</p>
      </Section>

      <Section id="rights" title="Your rights">
        <ul className="list-disc space-y-1.5 pl-5">
          <li>
            <strong>Access and portability</strong>: download everything we keep about you as JSON (Settings → Privacy →
            Download my data).
          </li>
          <li>
            <strong>Erasure</strong>: delete your account and its data (Settings → Privacy → Delete account). Uploaded
            files are deleted at once. Audit events stay for their retention period without your identity. Stripe keeps
            invoices as tax law requires.
          </li>
          <li>
            <strong>Withdraw consent</strong> for AI photo processing or emails at any time, without affecting what was
            done before.
          </li>
          <li>
            <strong>Rectification, restriction and objection</strong>: edit your profile in Settings, or contact us.
          </li>
          <li>
            <strong>Complaint</strong>: to the Hellenic Data Protection Authority (www.dpa.gr) or the authority where you
            live.
          </li>
        </ul>
      </Section>

      <Section id="cookies" title="Cookies and browser storage">
        <p>
          The website sets no advertising or analytics cookies, so there is no cookie banner. It uses browser storage
          only for things the service needs to work: your sign-in session, preferences and, in this version, your saved
          properties. Signing out removes the session. &quot;Reset&quot; in Settings clears the rest.
        </p>
      </Section>

      <Section id="security" title="How we protect it">
        <ul className="list-disc space-y-1.5 pl-5">
          <li>Passwords are stored as BCrypt hashes. Session tokens and API keys are stored only as SHA-256 hashes.</li>
          <li>
            Uploaded files are private. They are served only to the account that uploaded them, after a sign-in check.
            Every access is recorded in the audit log.
          </li>
          <li>
            Repeated failed sign-ins are slowed down and locked. Plan limits and consents are enforced by the server.
          </li>
          <li>Backups are encrypted and kept for 14 days. A restore is tested automatically.</li>
        </ul>
      </Section>
    </LegalPage>
  );
}
