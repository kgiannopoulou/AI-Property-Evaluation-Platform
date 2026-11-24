/**
 * Product modules.
 *   engine:  implemented as a backend module (Java, or the AI service) with an API; needs the backend
 *   preview: shown in the browser's illustrative sample report only
 *   planned: on the roadmap
 */
export type ModuleStatus = "engine" | "preview" | "planned";

export const MODULE_STATUS_LABELS: Record<ModuleStatus, string> = {
  engine: "Engine",
  preview: "Preview",
  planned: "Planned",
};

export const MODULES: { name: string; description: string; status: ModuleStatus }[] = [
  {
    name: "Valuation engine",
    description: "Fair value from comparable sales and price per m², with a data-quality check that refuses thin evidence.",
    status: "engine",
  },
  {
    name: "AI Home Inspector",
    description: "A vision model finds visible defects and missing equipment in your photographs.",
    status: "engine",
  },
  {
    name: "Location Intelligence",
    description: "Nearby services, transport, parks and schools with walking times, plus mapped environmental risks.",
    status: "engine",
  },
  {
    name: "Financial Calculator",
    description: "Purchase taxes and fees, mortgage with a stress test, and the true monthly cost of ownership.",
    status: "engine",
  },
  {
    name: "Renovation Planner",
    description: "Basic, medium and premium scenarios with itemised costs and value after works.",
    status: "engine",
  },
  {
    name: "Market Comparator",
    description: "Price per m² of nearby completed deals and asking prices, and where this property sits among them.",
    status: "engine",
  },
  {
    name: "Investment Calculator",
    description: "Rental yield, ROI, vacancy, cash flow, IRR and a year-by-year projection.",
    status: "engine",
  },
  {
    name: "Negotiation Assistant",
    description: "Offer suggestions, evidence-based arguments, contract conditions and walk-away signals.",
    status: "engine",
  },
  {
    name: "Building Analysis",
    description: "Elevator, roof, insulation, seismic design, maintenance and shared costs.",
    status: "engine",
  },
  {
    name: "Document Checker",
    description: "Flags missing, expired, inconsistent or questionable title, permit and certificate documents.",
    status: "engine",
  },
  {
    name: "Floor Plan Reader",
    description: "Reads room layouts and printed measurements from a floor plan and checks them against the listing.",
    status: "engine",
  },
  {
    name: "Property Comparison",
    description: "Compare several properties side by side and see which one wins on each measure.",
    status: "engine",
  },
  {
    name: "Reports",
    description: "A downloadable PDF evaluation report to share with a partner, bank or adviser.",
    status: "engine",
  },
  {
    name: "Plans & subscriptions",
    description: "Free, Premium, Professional and Business plans for individuals, agents and firms.",
    status: "preview",
  },
];
