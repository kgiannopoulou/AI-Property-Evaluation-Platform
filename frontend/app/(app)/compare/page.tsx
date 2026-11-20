import type { Metadata } from "next";
import { Suspense } from "react";
import { CompareView } from "./compare-view";

export const metadata: Metadata = { title: "Compare properties" };

export default function ComparePage() {
  return (
    <Suspense fallback={<div className="h-96 animate-pulse rounded-xl bg-line/60" aria-busy="true" />}>
      <CompareView />
    </Suspense>
  );
}
