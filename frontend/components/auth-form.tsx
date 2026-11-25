"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Logo } from "./icons";
import { Button, Field } from "./ui";
import { register, login } from "@/lib/account";
import { API_URL, ApiError } from "@/lib/api";
import { signIn } from "@/lib/session";

type Mode = "login" | "register";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function AuthForm({ mode }: { mode: Mode }) {
  const router = useRouter();
  const [errors, setErrors] = useState<Record<string, string>>({});

  const [busy, setBusy] = useState(false);
  const minPassword = API_URL ? 10 : 8;

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const name = String(data.get("name") ?? "");
    const email = String(data.get("email") ?? "");
    const password = String(data.get("password") ?? "");
    const next: Record<string, string> = {};
    if (mode === "register" && name.trim().length < 2) next.name = "Enter your name.";
    if (!EMAIL_RE.test(email)) next.email = "Enter a valid email address.";
    if (password.length < (mode === "register" ? minPassword : 1))
      next.password = mode === "register" ? `Use at least ${minPassword} characters.` : "Enter your password.";
    if (mode === "register" && !data.get("terms")) next.terms = "Accept the terms and privacy notice to continue.";
    setErrors(next);
    if (Object.keys(next).length) return;

    if (!API_URL) {
      // Browser-only demo: no credential check.
      signIn(email, mode === "register" ? name : undefined);
      router.push("/dashboard");
      return;
    }
    setBusy(true);
    try {
      if (mode === "register")
        await register(name, email, password, {
          aiPhotoProcessing: !!data.get("aiPhotoProcessing"),
          marketingEmails: !!data.get("marketingEmails"),
        });
      else await login(email, password);
      router.push("/dashboard");
    } catch (err) {
      setErrors({ form: err instanceof ApiError ? err.message : "Something went wrong. Please try again." });
    } finally {
      setBusy(false);
    }
  }

  const isRegister = mode === "register";
  return (
    <div className="grid min-h-screen lg:grid-cols-2">
      <div className="flex flex-col justify-center px-6 py-12 sm:px-12">
        <div className="mx-auto w-full max-w-sm">
          <Link href="/">
            <Logo />
          </Link>
          <h1 className="mt-10 text-2xl font-semibold tracking-tight">
            {isRegister ? "Create your account" : "Welcome back"}
          </h1>
          <p className="mt-1.5 text-sm text-muted">
            {isRegister ? "Free plan, no card required." : "Sign in to your analyses and saved properties."}
          </p>

          <form onSubmit={onSubmit} noValidate className="mt-8 space-y-4">
            {isRegister && (
              <Field label="Full name" htmlFor="name" error={errors.name}>
                <input id="name" name="name" className="input" autoComplete="name" aria-invalid={!!errors.name} />
              </Field>
            )}
            <Field label="Email" htmlFor="email" error={errors.email}>
              <input
                id="email"
                name="email"
                type="email"
                className="input"
                autoComplete="email"
                aria-invalid={!!errors.email}
              />
            </Field>
            <Field
              label="Password"
              htmlFor="password"
              error={errors.password}
              hint={isRegister ? `At least ${minPassword} characters.` : undefined}
            >
              <input
                id="password"
                name="password"
                type="password"
                className="input"
                autoComplete={isRegister ? "new-password" : "current-password"}
                aria-invalid={!!errors.password}
              />
            </Field>
            {isRegister && (
              <fieldset className="space-y-2.5">
                <legend className="sr-only">Terms and consent</legend>
                <div>
                  <label className="flex items-start gap-2 text-sm text-muted">
                    <input
                      type="checkbox"
                      name="terms"
                      className="mt-0.5 accent-brand-600"
                      aria-invalid={!!errors.terms}
                      aria-describedby={errors.terms ? "terms-error" : undefined}
                    />
                    <span>
                      I accept the{" "}
                      <Link href="/terms" className="font-medium text-brand-700 hover:underline" target="_blank">
                        terms
                      </Link>{" "}
                      and have read the{" "}
                      <Link href="/privacy" className="font-medium text-brand-700 hover:underline" target="_blank">
                        privacy notice
                      </Link>
                      . I understand that valuations are automated estimates, not professional appraisals, and that
                      the document checker is an assistance tool, not legal advice. (required)
                    </span>
                  </label>
                  {errors.terms && (
                    <p id="terms-error" className="mt-1 text-xs text-negative">
                      {errors.terms}
                    </p>
                  )}
                </div>
                {API_URL && (
                  <>
                    <label className="flex items-start gap-2 text-sm text-muted">
                      <input type="checkbox" name="aiPhotoProcessing" className="mt-0.5 accent-brand-600" />
                      <span>
                        Allow photos and floor plans I upload to be analysed by our AI provider (optional; you can
                        decide later)
                      </span>
                    </label>
                    <label className="flex items-start gap-2 text-sm text-muted">
                      <input type="checkbox" name="marketingEmails" className="mt-0.5 accent-brand-600" />
                      <span>Send me occasional product news by email (optional)</span>
                    </label>
                  </>
                )}
              </fieldset>
            )}
            {errors.form && (
              <p role="alert" className="rounded-lg border border-negative/20 bg-negative-soft px-3 py-2 text-sm text-negative">
                {errors.form}
              </p>
            )}
            <Button type="submit" size="lg" className="w-full" disabled={busy}>
              {busy ? "Please wait…" : isRegister ? "Create account" : "Sign in"}
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-muted">
            {isRegister ? "Already have an account? " : "New here? "}
            <Link href={isRegister ? "/login" : "/register"} className="font-medium text-brand-700 hover:underline">
              {isRegister ? "Sign in" : "Create an account"}
            </Link>
          </p>
          <p className="mt-8 rounded-lg bg-canvas px-3 py-2.5 text-xs text-muted">
            {API_URL
              ? "Your account is stored by the platform; passwords are kept only as a secure hash."
              : "Browser-only demo: accounts are stored in this browser and passwords are not checked or saved."}
          </p>
        </div>
      </div>
      <div className="relative hidden overflow-hidden bg-ink-900 lg:block">
        <div
          aria-hidden
          className="absolute inset-0 opacity-[0.07]"
          style={{
            backgroundImage:
              "linear-gradient(to right, white 1px, transparent 1px), linear-gradient(to bottom, white 1px, transparent 1px)",
            backgroundSize: "48px 48px",
          }}
        />
        <div className="relative flex h-full flex-col justify-end p-14 text-white">
          <p className="max-w-md text-2xl leading-snug font-medium tracking-tight">
            “The asking price is where the conversation starts. The evidence is what lets you change it.”
          </p>
          <ul className="mt-10 space-y-3 text-sm text-white/65">
            {["Valuation range with confidence", "Ownership and renovation costs", "Opening and maximum offer"].map(
              (t) => (
                <li key={t} className="flex items-center gap-2">
                  <span className="h-1.5 w-1.5 rounded-full bg-brand-500" />
                  {t}
                </li>
              ),
            )}
          </ul>
        </div>
      </div>
    </div>
  );
}
