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

export const LISTING_TYPES = ["sale", "rent"] as const;
export type ListingType = (typeof LISTING_TYPES)[number];

export const CONSTRUCTION_TYPES = ["reinforced-concrete", "masonry", "steel", "timber", "mixed", "unknown"] as const;
export type ConstructionType = (typeof CONSTRUCTION_TYPES)[number];

export const COOLING_TYPES = ["none", "split-units", "central-ac", "heat-pump", "ceiling-fans"] as const;
export type CoolingType = (typeof COOLING_TYPES)[number];

export const CERTIFICATE_STATUSES = ["issued", "pending", "none", "unknown"] as const;
export type CertificateStatus = (typeof CERTIFICATE_STATUSES)[number];

/* Appliance checklist ------------------------------------------------------- */

export const APPLIANCES = ["refrigerator", "washing-machine", "dishwasher", "oven-stove", "air-conditioning"] as const;
export type Appliance = (typeof APPLIANCES)[number];

export const APPLIANCE_STATUSES = ["working", "damaged", "missing", "unknown"] as const;
export type ApplianceStatus = (typeof APPLIANCE_STATUSES)[number];

export const HEATING_STATUSES = ["available", "unavailable", "unknown"] as const;
export type HeatingStatus = (typeof HEATING_STATUSES)[number];

export const FURNITURE_STATUSES = ["included", "partial", "not-included"] as const;
export type FurnitureStatus = (typeof FURNITURE_STATUSES)[number];

export type EquipmentChecklist = {
  appliances: Record<Appliance, ApplianceStatus>;
  heating: HeatingStatus;
  furniture: FurnitureStatus;
  /** Other furniture and appliances that come with the property, free text. */
  otherItems: string;
};

/* Facilities ---------------------------------------------------------------- */

export const FACILITIES = [
  "garden",
  "roof-terrace",
  "pool",
  "fireplace",
  "solar-water-heater",
  "security-door",
  "alarm",
  "double-glazing",
  "awnings",
  "playground",
  "accessible-entrance",
  "pets-allowed",
] as const;
export type Facility = (typeof FACILITIES)[number];

/* Photographs --------------------------------------------------------------- */

export const PHOTO_AREAS = [
  "kitchen",
  "bathroom",
  "bedroom",
  "living-room",
  "exterior",
  "building-entrance",
  "other",
] as const;
export type PhotoArea = (typeof PHOTO_AREAS)[number];

export type PropertyPhoto = {
  id: string;
  name: string;
  /** Which part of the property the photo shows; the AI Home Inspector uses it. */
  area: PhotoArea;
  /** Downscaled JPEG data URL — originals are never kept in browser storage. */
  dataUrl: string;
};

export type GeoPoint = { lat: number; lon: number };

export type BuildingExpenses = {
  /** Monthly common charges (koinochrista), EUR. null = not known. */
  monthlyCommonCharges: number | null;
  /** Whether the common charges include central heating. */
  includesHeating: boolean;
  /** Planned or ongoing building works and special levies. */
  plannedWorks: string;
};

export type PropertyInput = {
  title: string;
  /** Purchase (asking price) or rental (asking monthly rent). */
  listingType: ListingType;
  address: string;
  district: string;
  location: GeoPoint | null;
  propertyType: PropertyType;
  sizeSqm: number;
  bedrooms: number;
  bathrooms: number;
  floor: number;
  yearBuilt: number;
  renovatedYear: number | null;
  constructionType: ConstructionType;
  condition: Condition;
  energyCertificate: { status: CertificateStatus; number: string };
  /** Rating from the certificate, or the owner's best estimate if there is none. */
  energyClass: EnergyClass;
  heating: HeatingType;
  cooling: CoolingType;
  parking: boolean;
  storage: boolean;
  elevator: boolean;
  balconySqm: number;
  /** Sale price for a purchase, monthly rent for a rental, EUR. */
  askingPrice: number;
  checklist: EquipmentChecklist;
  buildingExpenses: BuildingExpenses;
  facilities: Facility[];
  otherFacilities: string;
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

export const LISTING_TYPE_LABELS: Record<ListingType, string> = {
  sale: "For sale",
  rent: "For rent",
};

export const CONSTRUCTION_LABELS: Record<ConstructionType, string> = {
  "reinforced-concrete": "Reinforced concrete",
  masonry: "Masonry / stone",
  steel: "Steel frame",
  timber: "Timber",
  mixed: "Mixed",
  unknown: "Not known",
};

export const COOLING_LABELS: Record<CoolingType, string> = {
  none: "None",
  "split-units": "Split air-conditioning units",
  "central-ac": "Central air-conditioning",
  "heat-pump": "Heat pump (heating and cooling)",
  "ceiling-fans": "Ceiling fans only",
};

export const CERTIFICATE_LABELS: Record<CertificateStatus, string> = {
  issued: "Issued",
  pending: "Applied for",
  none: "No certificate",
  unknown: "Not known",
};

export const APPLIANCE_LABELS: Record<Appliance, string> = {
  refrigerator: "Refrigerator",
  "washing-machine": "Washing machine",
  dishwasher: "Dishwasher",
  "oven-stove": "Oven and stove",
  "air-conditioning": "Air conditioning",
};

export const APPLIANCE_STATUS_LABELS: Record<ApplianceStatus, string> = {
  working: "Included and working",
  damaged: "Damaged",
  missing: "Missing",
  unknown: "Unknown",
};

export const HEATING_STATUS_LABELS: Record<HeatingStatus, string> = {
  available: "Available",
  unavailable: "Unavailable",
  unknown: "Unknown",
};

export const FURNITURE_STATUS_LABELS: Record<FurnitureStatus, string> = {
  included: "Included",
  partial: "Partially included",
  "not-included": "Not included",
};

export const FACILITY_LABELS: Record<Facility, string> = {
  garden: "Garden",
  "roof-terrace": "Roof terrace",
  pool: "Swimming pool",
  fireplace: "Fireplace",
  "solar-water-heater": "Solar water heater",
  "security-door": "Security door",
  alarm: "Alarm system",
  "double-glazing": "Double glazing",
  awnings: "Awnings",
  playground: "Shared playground",
  "accessible-entrance": "Step-free entrance",
  "pets-allowed": "Pets allowed",
};

export const PHOTO_AREA_LABELS: Record<PhotoArea, string> = {
  kitchen: "Kitchen",
  bathroom: "Bathroom",
  bedroom: "Bedrooms",
  "living-room": "Living room",
  exterior: "Exterior",
  "building-entrance": "Building entrance",
  other: "Other areas",
};
