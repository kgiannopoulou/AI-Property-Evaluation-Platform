"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Logo } from "./icons";
import { Button, Field } from "./ui";
import { signIn } from "@/lib/session";

type Mode = "login" | "register";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function AuthForm({ mode }: { mode: Mode }) {
  const router = useRouter();
  const [errors, setErrors] = useState<Record<string, string>>({});

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const name = String(data.get("name") ?? "");
    const email = String(data.get("email") ?? "");
    const password = String(data.get("password") ?? "");
    const next: Record<string, string> = {};
    if (mode === "register" && name.trim().length < 2) next.name = "Enter your name.";
    if (!EMAIL_RE.test(email)) next.email = "Enter a valid email address.";
    if (password.length < (mode === "register" ? 8 : 1))
      next.password = mode === "register" ? "Use at least 8 characters." : "Enter your password.";
    if (mode === "register" && !data.get("terms")) next.terms = "Please accept the terms to continue.";
    setErrors(next);
    if (Object.keys(next).length) return;

    // Prototype: no credential check. The Spring Boot users module will own authentication.
    signIn(email, mode === "register" ? name : undefined);
    router.push("/dashboard");
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
              hint={isRegister ? "At least 8 characters." : undefined}
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
              <div>
                <label className="flex items-start gap-2 text-sm text-muted">
                  <input type="checkbox" name="terms" className="mt-0.5 accent-brand-600" />
                  I agree that results are estimates, not a formal valuation or financial advice.
                </label>
                {errors.terms && <p className="mt-1 text-xs text-negative">{errors.terms}</p>}
              </div>
            )}
            <Button type="submit" size="lg" className="w-full">
              {isRegister ? "Create account" : "Sign in"}
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-muted">
            {isRegister ? "Already have an account? " : "New here? "}
            <Link href={isRegister ? "/login" : "/register"} className="font-medium text-brand-700 hover:underline">
              {isRegister ? "Sign in" : "Create an account"}
            </Link>
          </p>
          <p className="mt-8 rounded-lg bg-canvas px-3 py-2.5 text-xs text-muted">
            Prototype: accounts are stored only in this browser and passwords are not checked or saved.
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
