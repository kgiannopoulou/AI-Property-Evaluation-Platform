import Link from "next/link";
import { ValueRangeBar } from "@/components/charts";
import { IconArrowRight, IconCheck } from "@/components/icons";
import { SiteFooter, SiteHeader } from "@/components/site-chrome";
import { Badge, ButtonLink, Card } from "@/components/ui";
import { MODULE_STATUS_LABELS, MODULES } from "@/lib/modules";

export default function HomePage() {
  return (
    <>
      <SiteHeader />
      <main>
        <Hero />
        <HowItWorks />
        <Modules />
        <Cta />
      </main>
      <SiteFooter />
    </>
  );
}

function Hero() {
  return (
    <section className="relative overflow-hidden bg-ink-900 text-white">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-[0.07]"
        style={{
          backgroundImage:
            "linear-gradient(to right, white 1px, transparent 1px), linear-gradient(to bottom, white 1px, transparent 1px)",
          backgroundSize: "48px 48px",
        }}
      />
      <div className="relative mx-auto grid max-w-6xl items-center gap-12 px-4 pt-16 pb-20 sm:px-6 lg:grid-cols-[1.05fr_1fr] lg:pt-24 lg:pb-28">
        <div>
          <Badge tone="brand" className="border-brand-500/30 bg-brand-500/10 text-brand-100">
            Property due diligence, in one report
          </Badge>
          <h1 className="mt-5 text-4xl leading-[1.1] font-semibold tracking-tight sm:text-5xl">
            Know what a property is worth <span className="text-brand-500">before you make an offer.</span>
          </h1>
          <p className="mt-5 max-w-xl text-lg leading-relaxed text-white/70">
            Enter the details, upload photographs and get an independent view of the asking price, ownership costs,
            renovation budget and a negotiation range you can defend.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <ButtonLink href="/properties/new" size="lg">
              Analyze a property <IconArrowRight />
            </ButtonLink>
            <ButtonLink
              href="/dashboard"
              size="lg"
              variant="secondary"
              className="border-white/20 bg-white/5 text-white hover:bg-white/10"
            >
              View demo dashboard
            </ButtonLink>
          </div>
          <ul className="mt-8 flex flex-wrap gap-x-6 gap-y-2 text-sm text-white/60">
            {["No card required", "Free plan: 3 analyses a month", "Built for buyers and investors"].map((t) => (
              <li key={t} className="flex items-center gap-1.5">
                <IconCheck className="text-brand-500" width={16} height={16} />
                {t}
              </li>
            ))}
          </ul>
        </div>
        <ReportPreview />
      </div>
    </section>
  );
}

/** Static illustration of a results card (sample numbers, labelled). */
function ReportPreview() {
  return (
    <Card className="relative border-white/10 p-6 text-text shadow-2xl shadow-black/30">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs font-medium tracking-wide text-muted uppercase">Valuation preview</p>
          <p className="mt-1 font-semibold">2-bed apartment · 82 m² · Pagrati</p>
        </div>
        <Badge tone="gold">Sample</Badge>
      </div>
      <div className="mt-5 grid grid-cols-3 gap-4 border-y border-line py-4">
        <div>
          <p className="text-xs text-muted">Estimated value</p>
          <p className="tabular mt-1 text-xl font-semibold">€279k</p>
        </div>
        <div>
          <p className="text-xs text-muted">Asking price</p>
          <p className="tabular mt-1 text-xl font-semibold">€285k</p>
        </div>
        <div>
          <p className="text-xs text-muted">Verdict</p>
          <p className="mt-1.5">
            <Badge tone="positive">Fair price</Badge>
          </p>
        </div>
      </div>
      <ValueRangeBar low={256000} mid={279000} high={302000} asking={285000} />
      <div className="grid grid-cols-3 gap-3 text-sm">
        {[
          ["Opening offer", "€256k"],
          ["Monthly cost", "€1,232"],
          ["Gross yield", "4.4%"],
        ].map(([k, v]) => (
          <div key={k} className="rounded-lg bg-canvas px-3 py-2.5">
            <p className="text-xs text-muted">{k}</p>
            <p className="tabular font-semibold">{v}</p>
          </div>
        ))}
      </div>
    </Card>
  );
}

function HowItWorks() {
  const steps = [
    {
      n: "01",
      title: "Describe the property",
      body: "Size, floor, year, condition, energy class and the asking price. Add photographs of every room.",
    },
    {
      n: "02",
      title: "We evaluate it",
      body: "Valuation against comparable homes, inspection signals, ownership and renovation costs, rental yield.",
    },
    {
      n: "03",
      title: "Decide and negotiate",
      body: "A clear verdict, an opening and maximum offer, and the arguments to support them — as a report.",
    },
  ];
  return (
    <section id="how-it-works" className="mx-auto max-w-6xl scroll-mt-16 px-4 py-20 sm:px-6">
      <p className="text-sm font-semibold text-brand-600">How it works</p>
      <h2 className="mt-2 max-w-xl text-3xl font-semibold tracking-tight">From listing to a defensible offer.</h2>
      <div className="mt-10 grid gap-6 md:grid-cols-3">
        {steps.map((s) => (
          <div key={s.n} className="border-t-2 border-ink-900 pt-5">
            <span className="tabular font-mono text-sm text-brand-600">{s.n}</span>
            <h3 className="mt-2 text-lg font-semibold">{s.title}</h3>
            <p className="mt-2 text-sm leading-relaxed text-muted">{s.body}</p>
          </div>
        ))}
      </div>
    </section>
  );
}

function Modules() {
  return (
    <section id="modules" className="scroll-mt-16 border-y border-line bg-surface">
      <div className="mx-auto max-w-6xl px-4 py-20 sm:px-6">
        <div className="flex flex-col justify-between gap-4 md:flex-row md:items-end">
          <div>
            <p className="text-sm font-semibold text-brand-600">The platform</p>
            <h2 className="mt-2 max-w-xl text-3xl font-semibold tracking-tight">
              Every question a careful buyer asks, in one place.
            </h2>
          </div>
          <p className="max-w-sm text-sm text-muted">
            Modules marked <em>Engine</em> run in the backend with real data; <em>Preview</em> modules appear in the browser&apos;s sample report.
          </p>
        </div>
        <div className="mt-10 grid gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-2 lg:grid-cols-3">
          {MODULES.map((m) => (
            <div key={m.name} className="bg-surface p-5">
              <div className="flex items-center justify-between gap-2">
                <h3 className="font-semibold">{m.name}</h3>
                <Badge tone={m.status === "engine" ? "positive" : m.status === "preview" ? "brand" : "neutral"}>
                  {MODULE_STATUS_LABELS[m.status]}
                </Badge>
              </div>
              <p className="mt-2 text-sm leading-relaxed text-muted">{m.description}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function Cta() {
  return (
    <section className="mx-auto max-w-6xl px-4 py-20 sm:px-6">
      <div className="flex flex-col items-start justify-between gap-6 rounded-2xl bg-ink-900 px-8 py-10 text-white md:flex-row md:items-center">
        <div>
          <h2 className="text-2xl font-semibold tracking-tight">Evaluate your first property in minutes.</h2>
          <p className="mt-2 text-white/65">
            Free plan included. Upgrade when you are analysing more than a few homes.
          </p>
        </div>
        <div className="flex gap-3">
          <ButtonLink href="/register" size="lg" variant="inverse">
            Create free account
          </ButtonLink>
          <Link
            href="/pricing"
            className="inline-flex items-center px-2 text-sm font-medium text-white/80 hover:text-white"
          >
            See plans
          </Link>
        </div>
      </div>
    </section>
  );
}
