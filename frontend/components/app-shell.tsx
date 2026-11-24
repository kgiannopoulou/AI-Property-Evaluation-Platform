"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Suspense, useState, type ReactNode } from "react";
import { cn } from "@/lib/format";
import { DEMO_SESSION, PLAN_LIMITS, signOut, useSession } from "@/lib/session";
import {
  IconCompare,
  IconDashboard,
  IconHome,
  IconLogout,
  IconMenu,
  IconPlus,
  IconSettings,
  IconTag,
  IconX,
  Logo,
} from "./icons";

const NAV = [
  { href: "/dashboard", label: "Dashboard", icon: IconDashboard },
  { href: "/properties/new", label: "Analyze a property", icon: IconPlus },
  { href: "/properties", label: "Saved properties", icon: IconHome },
  { href: "/compare", label: "Compare", icon: IconCompare },
  { href: "/pricing", label: "Plans & billing", icon: IconTag },
  { href: "/settings", label: "Profile & settings", icon: IconSettings },
];

/** Shown in the sidebar: backend engines that have no page yet, and the rest of the roadmap. */
const ROADMAP = ["Document upload & checks", "Floor plan upload", "Accounts & cloud sync", "Subscriptions & payments"];

function isActive(pathname: string, href: string) {
  if (href === "/properties") return pathname === "/properties";
  return pathname === href || pathname.startsWith(`${href}/`);
}

export function AppShell({ children }: { children: ReactNode }) {
  const router = useRouter();
  const session = useSession();
  const user = session ?? DEMO_SESSION;
  const [open, setOpen] = useState(false);

  const sidebar = (
    <div className="flex h-full flex-col">
      <div className="flex h-16 items-center justify-between px-5">
        <Link href="/" onClick={() => setOpen(false)}>
          <Logo inverted />
        </Link>
        <button className="text-white/70 lg:hidden" onClick={() => setOpen(false)} aria-label="Close menu">
          <IconX />
        </button>
      </div>
      <nav className="mt-2 flex-1 space-y-0.5 px-3" aria-label="Main">
        <Suspense fallback={<NavLinks active={null} onNavigate={() => setOpen(false)} />}>
          <ActiveNavLinks onNavigate={() => setOpen(false)} />
        </Suspense>
        <p className="px-3 pt-6 pb-2 text-[11px] font-semibold tracking-wider text-white/35 uppercase">Coming next</p>
        {ROADMAP.map((m) => (
          <span key={m} className="flex items-center justify-between px-3 py-1.5 text-sm text-white/40">
            {m}
            <span className="rounded bg-white/5 px-1.5 py-0.5 text-[10px] tracking-wide uppercase">Soon</span>
          </span>
        ))}
      </nav>
      <div className="border-t border-white/10 p-4">
        <div className="flex items-center gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-600 text-sm font-semibold text-white">
            {user.name.slice(0, 1).toUpperCase()}
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-white">{user.name}</p>
            <p className="truncate text-xs text-white/50">{PLAN_LIMITS[user.plan].label} plan</p>
          </div>
          {session ? (
            <button
              onClick={() => {
                signOut();
                router.push("/");
              }}
              className="text-white/50 hover:text-white"
              aria-label="Sign out"
              title="Sign out"
            >
              <IconLogout />
            </button>
          ) : (
            <Link href="/login" className="text-xs font-medium text-brand-500 hover:text-brand-100">
              Sign in
            </Link>
          )}
        </div>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen lg:pl-64">
      <aside className="no-print fixed inset-y-0 left-0 z-40 hidden w-64 bg-ink-900 lg:block">{sidebar}</aside>

      {open && (
        <div className="no-print fixed inset-0 z-50 lg:hidden">
          <div className="absolute inset-0 bg-ink-950/60" onClick={() => setOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-72 bg-ink-900">{sidebar}</aside>
        </div>
      )}

      <header className="no-print sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-line bg-surface/90 px-4 backdrop-blur lg:hidden">
        <button onClick={() => setOpen(true)} aria-label="Open menu" className="text-muted">
          <IconMenu />
        </button>
        <Logo />
      </header>

      {!session && (
        <div className="no-print border-b border-gold-500/20 bg-gold-100/70 px-4 py-2 text-center text-sm text-ink-700 sm:px-8">
          You are exploring with a demo account. Data stays in this browser.{" "}
          <Link href="/register" className="font-semibold text-ink-900 underline underline-offset-2">
            Create an account
          </Link>
        </div>
      )}

      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-8">{children}</main>
    </div>
  );
}

/** Reads the URL, so it lives inside a Suspense boundary (Cache Components). */
function ActiveNavLinks({ onNavigate }: { onNavigate: () => void }) {
  return <NavLinks active={usePathname()} onNavigate={onNavigate} />;
}

function NavLinks({ active: pathname, onNavigate }: { active: string | null; onNavigate: () => void }) {
  return (
    <>
      {NAV.map(({ href, label, icon: NavIcon }) => {
        const active = pathname !== null && isActive(pathname, href);
        return (
          <Link
            key={href}
            href={href}
            onClick={onNavigate}
            aria-current={active ? "page" : undefined}
            className={cn(
              "flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors",
              active ? "bg-white/10 font-medium text-white" : "text-white/65 hover:bg-white/5 hover:text-white",
            )}
          >
            <NavIcon className={active ? "text-brand-500" : undefined} />
            {label}
          </Link>
        );
      })}
    </>
  );
}
