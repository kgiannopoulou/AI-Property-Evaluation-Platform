import type { Metadata } from "next";
import { Suspense } from "react";
import { PropertyReport } from "./property-report";

export const metadata: Metadata = { title: "Analysis results" };

export default function PropertyPage({ params }: PageProps<"/properties/[id]">) {
  return (
    <Suspense fallback={<div className="h-96 animate-pulse rounded-xl bg-line/60" aria-busy="true" />}>
      {params.then(({ id }) => (
        <PropertyReport id={id} />
      ))}
    </Suspense>
  );
}
