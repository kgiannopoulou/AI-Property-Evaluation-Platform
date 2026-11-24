import type { Metadata } from "next";
import { Suspense } from "react";
import { SettingsView } from "./settings-view";

export const metadata: Metadata = { title: "Profile & settings" };

export default function SettingsPage() {
  return (
    <Suspense fallback={<div className="h-96 animate-pulse rounded-xl bg-line/60" aria-busy="true" />}>
      <SettingsView />
    </Suspense>
  );
}
