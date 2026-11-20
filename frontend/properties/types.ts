/**
 * Property domain types shared by the form, the saved-properties list,
 * the comparison view and the analysis engine.
 *
 * These mirror the planned `properties` table (database/migrations) so the
 * Phase 4 switch from browser storage to the Java API is a transport change.
 */

export const PROPERTY_TYPES = ["apartment", "maisonette", "detached-house", "studio", "penthouse"] as const;
export type PropertyType = (typeof PROPERTY_TYPES)[number];

export const CONDITIONS = ["needs-renovation", "fair", "good", "excellent"] as const;
export type Condition = (typeof CONDITIONS)[number];

export const ENERGY_CLASSES = ["A+", "A", "B+", "B", "C", "D", "E", "F", "G"] as const;
export type EnergyClass = (typeof ENERGY_CLASSES)[number];

export const HEATING_TYPES = ["central-oil", "autonomous-gas", "heat-pump", "electric", "none"] as const;
export type HeatingType = (typeof HEATING_TYPES)[number];

export type PropertyPhoto = {
  id: string;
  name: string;
  /** Downscaled JPEG data URL — originals are never kept in browser storage. */
  dataUrl: string;
};

export type PropertyInput = {
  title: string;
  address: string;
  district: string;
  propertyType: PropertyType;
  sizeSqm: number;
  bedrooms: number;
  bathrooms: number;
  floor: number;
  yearBuilt: number;
  renovatedYear: number | null;
  condition: Condition;
  energyClass: EnergyClass;
  heating: HeatingType;
  parking: boolean;
  storage: boolean;
  elevator: boolean;
  balconySqm: number;
  askingPrice: number;
  notes: string;
  photos: PropertyPhoto[];
};

export type Property = PropertyInput & {
  id: string;
  createdAt: string;
  /** Seeded demo records are flagged so the UI can label them honestly. */
  isSample: boolean;
};

export const PROPERTY_TYPE_LABELS: Record<PropertyType, string> = {
  apartment: "Apartment",
  maisonette: "Maisonette",
  "detached-house": "Detached house",
  studio: "Studio",
  penthouse: "Penthouse",
};

export const CONDITION_LABELS: Record<Condition, string> = {
  "needs-renovation": "Needs renovation",
  fair: "Fair",
  good: "Good",
  excellent: "Excellent / renovated",
};

export const HEATING_LABELS: Record<HeatingType, string> = {
  "central-oil": "Central (oil)",
  "autonomous-gas": "Autonomous (natural gas)",
  "heat-pump": "Heat pump",
  electric: "Electric",
  none: "None",
};
