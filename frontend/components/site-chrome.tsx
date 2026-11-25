import Link from "next/link";
import { Logo } from "./icons";
import { ButtonLink } from "./ui";

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-30 border-b border-white/10 bg-ink-900/95 backdrop-blur">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-4 sm:px-6">
        <Link href="/" aria-label="Property Intelligence home">
          <Logo inverted />
        </Link>
        <nav className="hidden items-center gap-7 text-sm text-white/70 md:flex">
          <Link href="/#modules" className="hover:text-white">
            Product
          </Link>
          <Link href="/#how-it-works" className="hover:text-white">
            How it works
          </Link>
          <Link href="/pricing" className="hover:text-white">
            Pricing
          </Link>
        </nav>
        <div className="flex items-center gap-2">
          <Link href="/login" className="hidden px-3 text-sm font-medium text-white/80 hover:text-white sm:inline">
            Sign in
          </Link>
          <ButtonLink href="/register" size="sm">
            Start free
          </ButtonLink>
        </div>
      </div>
    </header>
  );
}

export function SiteFooter() {
  return (
    <footer className="border-t border-line bg-surface">
      <div className="mx-auto grid max-w-6xl gap-8 px-4 py-10 sm:px-6 sm:grid-cols-2 md:grid-cols-[1.4fr_1fr_1fr_1fr_1.3fr]">
        <div>
          <Logo />
          <p className="mt-3 max-w-xs text-sm text-muted">
            Independent analysis of asking prices, ownership costs and renovation risk before you commit.
          </p>
        </div>
        <FooterColumn
          title="Product"
          links={[
            ["Property Analyzer", "/properties/new"],
            ["Compare properties", "/compare"],
            ["Pricing", "/pricing"],
          ]}
        />
        <FooterColumn
          title="Account"
          links={[
            ["Sign in", "/login"],
            ["Create account", "/register"],
            ["Dashboard", "/dashboard"],
          ]}
        />
        <FooterColumn
          title="Legal"
          links={[
            ["Privacy notice", "/privacy"],
            ["Terms of use", "/terms"],
          ]}
        />
        <div>
          <h3 className="text-sm font-semibold">Disclaimer</h3>
          <p className="mt-3 text-xs leading-relaxed text-muted">
            Valuations are automated estimates, not professional appraisals. The document checker is an assistance tool,
            not a legal certification. Nothing here is a survey or financial advice.
          </p>
        </div>
      </div>
      <div className="border-t border-line py-4 text-center text-xs text-subtle">
        © <CopyrightYear /> Property Intelligence — portfolio project
      </div>
    </footer>
  );
}

function FooterColumn({ title, links }: { title: string; links: [string, string][] }) {
  return (
    <div>
      <h3 className="text-sm font-semibold">{title}</h3>
      <ul className="mt-3 space-y-2 text-sm text-muted">
        {links.map(([label, href]) => (
          <li key={href}>
            <Link href={href} className="hover:text-text">
              {label}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Prerendered and cached, so the static shell does not read the clock per request. */
async function CopyrightYear() {
  "use cache";
  return new Date().getFullYear();
}
