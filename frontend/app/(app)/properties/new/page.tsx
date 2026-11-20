import type { Metadata } from "next";
import { PageHeader, SampleNotice } from "@/components/ui";
import { PropertyForm } from "@/properties/property-form";

export const metadata: Metadata = { title: "Analyze a property" };

export default function NewPropertyPage() {
  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        eyebrow="Property Analyzer"
        title="Analyze a property"
        description="Enter the listing details. You will get a valuation range, a verdict on the asking price, ownership costs, renovation scenarios and an offer range."
      />
      <SampleNotice className="mb-6" />
      <PropertyForm />
    </div>
  );
}
