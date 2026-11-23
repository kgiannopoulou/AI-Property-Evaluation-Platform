/**
 * Property submission: form state, validation and record upgrades.
 *
 * Pure functions with no React or browser dependencies, so they run in the
 * form, in the store and under `node --test` (see submission.test.ts). The
 * Spring Boot API will apply the same rules server-side when it takes over
 * from browser storage.
 */
import {
  APPLIANCES,
  APPLIANCE_STATUSES,
  CERTIFICATE_STATUSES,
  CONDITIONS,
  CONSTRUCTION_TYPES,
  COOLING_TYPES,
  ENERGY_CLASSES,
  FACILITIES,
  FURNITURE_STATUSES,
  HEATING_STATUSES,
  HEATING_TYPES,
  LISTING_TYPES,
  PHOTO_AREAS,
  PROPERTY_TYPES,
  type EquipmentChecklist,
  type GeoPoint,
  type Property,
  type PropertyInput,
} from "./types.ts";

/** Numeric fields are kept as the text the user typed until validation. */
export type FormState = Omit<
  PropertyInput,
  | "location"
  | "sizeSqm"
  | "bedrooms"
  | "bathrooms"
  | "floor"
  | "yearBuilt"
  | "renovatedYear"
  | "balconySqm"
  | "askingPrice"
  | "buildingExpenses"
> & {
  coordinates: string;
  sizeSqm: string;
  bedrooms: string;
  bathrooms: string;
  floor: string;
  yearBuilt: string;
  renovatedYear: string;
  balconySqm: string;
  askingPrice: string;
  commonCharges: string;
  commonChargesIncludeHeating: boolean;
  plannedWorks: string;
};

export type FormErrors = Partial<Record<keyof FormState, string>>;

export const LIMITS = {
  sale: { min: 10_000, max: 20_000_000 },
  rent: { min: 100, max: 50_000 },
  maxPhotosPerArea: 8,
  maxPhotos: 24,
} as const;

export function defaultChecklist(): EquipmentChecklist {
  return {
    appliances: Object.fromEntries(APPLIANCES.map((a) => [a, "unknown"])) as EquipmentChecklist["appliances"],
    heating: "unknown",
    furniture: "not-included",
    otherItems: "",
  };
}

export const EMPTY_FORM: FormState = {
  title: "",
  listingType: "sale",
  address: "",
  district: "",
  coordinates: "",
  propertyType: "apartment",
  sizeSqm: "",
  bedrooms: "2",
  bathrooms: "1",
  floor: "1",
  yearBuilt: "",
  renovatedYear: "",
  constructionType: "reinforced-concrete",
  condition: "good",
  energyCertificate: { status: "issued", number: "" },
  energyClass: "C",
  heating: "autonomous-gas",
  cooling: "split-units",
  parking: false,
  storage: false,
  elevator: true,
  balconySqm: "0",
  askingPrice: "",
  checklist: defaultChecklist(),
  commonCharges: "",
  commonChargesIncludeHeating: false,
  plannedWorks: "",
  facilities: [],
  otherFacilities: "",
  notes: "",
  photos: [],
};

/** Values for the "Fill with example" button — clearly an example, not a listing. */
export const EXAMPLE_FORM: FormState = {
  ...EMPTY_FORM,
  title: "Example: bright two-bedroom with balcony",
  address: "Example street 5",
  district: "Nea Smyrni",
  coordinates: "37.9455, 23.7135",
  sizeSqm: "78",
  floor: "4",
  yearBuilt: "1985",
  renovatedYear: "2017",
  energyCertificate: { status: "issued", number: "" },
  energyClass: "C",
  balconySqm: "12",
  askingPrice: "265000",
  storage: true,
  checklist: {
    appliances: {
      refrigerator: "working",
      "washing-machine": "working",
      dishwasher: "missing",
      "oven-stove": "working",
      "air-conditioning": "damaged",
    },
    heating: "available",
    furniture: "partial",
    otherItems: "Built-in wardrobes in both bedrooms",
  },
  commonCharges: "45",
  facilities: ["security-door", "solar-water-heater", "awnings"],
  notes: "Example values for trying the form.",
};

function parseNumber(v: string): number {
  return v.trim() === "" ? NaN : Number(v.replace(/[\s,]/g, ""));
}

/**
 * Accepts "37.9755, 23.7348" (latitude, longitude — the format map apps copy)
 * or the same with a space instead of a comma. Returns undefined if invalid.
 */
export function parseCoordinates(v: string): GeoPoint | null | undefined {
  const t = v.trim();
  if (t === "") return null;
  const m = t.match(/^(-?\d+(?:\.\d+)?)\s*[,\s]\s*(-?\d+(?:\.\d+)?)$/);
  if (!m) return undefined;
  const lat = Number(m[1]);
  const lon = Number(m[2]);
  if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return undefined;
  return { lat: Math.round(lat * 1e6) / 1e6, lon: Math.round(lon * 1e6) / 1e6 };
}

export function formatCoordinates(p: GeoPoint | null): string {
  return p ? `${p.lat}, ${p.lon}` : "";
}

export function validateSubmission(
  f: FormState,
  currentYear: number,
): { errors: FormErrors; value?: PropertyInput } {
  const e: FormErrors = {};
  const size = parseNumber(f.sizeSqm);
  const price = parseNumber(f.askingPrice);
  const year = parseNumber(f.yearBuilt);
  const renovated = f.renovatedYear.trim() === "" ? null : parseNumber(f.renovatedYear);
  const floor = parseNumber(f.floor);
  const bedrooms = parseNumber(f.bedrooms);
  const bathrooms = parseNumber(f.bathrooms);
  const balcony = f.balconySqm.trim() === "" ? 0 : parseNumber(f.balconySqm);
  const charges = f.commonCharges.trim() === "" ? null : parseNumber(f.commonCharges);
  const location = parseCoordinates(f.coordinates);
  const limits = LIMITS[f.listingType];

  if (f.title.trim().length < 3) e.title = "Give the property a short name (3+ characters).";
  if (!f.district) e.district = "Choose the area.";
  if (location === undefined) e.coordinates = "Use “latitude, longitude”, e.g. 37.9755, 23.7348.";
  if (!Number.isFinite(size) || size < 15 || size > 2000) e.sizeSqm = "Enter a size between 15 and 2,000 m².";
  if (!Number.isFinite(price) || price < limits.min || price > limits.max)
    e.askingPrice =
      f.listingType === "sale"
        ? "Enter an asking price between €10,000 and €20,000,000."
        : "Enter a monthly rent between €100 and €50,000.";
  if (!Number.isInteger(year) || year < 1850 || year > currentYear + 3)
    e.yearBuilt = `Enter a year between 1850 and ${currentYear + 3}.`;
  if (
    renovated !== null &&
    (!Number.isInteger(renovated) || renovated > currentYear || (Number.isInteger(year) && renovated < year))
  )
    e.renovatedYear = "Must be after the build year and not in the future.";
  if (!Number.isInteger(floor) || floor < -2 || floor > 60) e.floor = "Enter a floor between -2 and 60 (0 = ground).";
  if (!Number.isInteger(bedrooms) || bedrooms < 0 || bedrooms > 20) e.bedrooms = "0–20";
  if (!Number.isInteger(bathrooms) || bathrooms < 0 || bathrooms > 10) e.bathrooms = "0–10";
  if (!Number.isFinite(balcony) || balcony < 0 || balcony > 500) e.balconySqm = "0–500 m²";
  if (charges !== null && (!Number.isFinite(charges) || charges < 0 || charges > 5000))
    e.commonCharges = "Enter a monthly amount between €0 and €5,000, or leave empty.";
  if (f.energyCertificate.number.trim().length > 40) e.energyCertificate = "Certificate number is too long.";
  if (f.photos.length > LIMITS.maxPhotos) e.photos = `Up to ${LIMITS.maxPhotos} photographs in total.`;

  if (Object.keys(e).length) return { errors: e };
  return {
    errors: e,
    value: {
      title: f.title.trim(),
      listingType: f.listingType,
      address: f.address.trim(),
      district: f.district,
      location: location ?? null,
      propertyType: f.propertyType,
      sizeSqm: size,
      bedrooms,
      bathrooms,
      floor,
      yearBuilt: year,
      renovatedYear: renovated,
      constructionType: f.constructionType,
      condition: f.condition,
      energyCertificate: {
        status: f.energyCertificate.status,
        number: f.energyCertificate.status === "issued" ? f.energyCertificate.number.trim() : "",
      },
      energyClass: f.energyClass,
      heating: f.heating,
      cooling: f.cooling,
      parking: f.parking,
      storage: f.storage,
      elevator: f.elevator,
      balconySqm: balcony,
      askingPrice: price,
      checklist: { ...f.checklist, otherItems: f.checklist.otherItems.trim() },
      buildingExpenses: {
        monthlyCommonCharges: charges,
        includesHeating: f.commonChargesIncludeHeating,
        plannedWorks: f.plannedWorks.trim(),
      },
      facilities: FACILITIES.filter((x) => f.facilities.includes(x)),
      otherFacilities: f.otherFacilities.trim(),
      notes: f.notes.trim(),
      photos: f.photos,
    },
  };
}

/* Upgrading stored records ------------------------------------------------------ */

const pick = <T extends string>(allowed: readonly T[], v: unknown, fallback: T): T =>
  allowed.includes(v as T) ? (v as T) : fallback;

/**
 * Brings a stored record up to the current shape. Records saved before the
 * submission system (Phase 3) have no listing type, checklist or photo areas;
 * they get neutral "unknown" defaults rather than invented values.
 */
export function normalizeProperty(raw: unknown): Property {
  const r = (raw ?? {}) as Partial<Property> & Record<string, unknown>;
  const checklist = (r.checklist ?? {}) as Partial<EquipmentChecklist>;
  const defaults = defaultChecklist();
  const expenses = (r.buildingExpenses ?? {}) as Partial<PropertyInput["buildingExpenses"]>;
  const cert = (r.energyCertificate ?? {}) as Partial<PropertyInput["energyCertificate"]>;
  const loc = r.location as GeoPoint | null | undefined;

  return {
    ...(r as Property),
    listingType: pick(LISTING_TYPES, r.listingType, "sale"),
    location:
      loc && Number.isFinite(loc.lat) && Number.isFinite(loc.lon) ? { lat: loc.lat, lon: loc.lon } : null,
    propertyType: pick(PROPERTY_TYPES, r.propertyType, "apartment"),
    constructionType: pick(CONSTRUCTION_TYPES, r.constructionType, "unknown"),
    condition: pick(CONDITIONS, r.condition, "good"),
    energyCertificate: { status: pick(CERTIFICATE_STATUSES, cert.status, "unknown"), number: cert.number ?? "" },
    energyClass: pick(ENERGY_CLASSES, r.energyClass, "C"),
    heating: pick(HEATING_TYPES, r.heating, "none"),
    cooling: pick(COOLING_TYPES, r.cooling, "none"),
    checklist: {
      appliances: Object.fromEntries(
        APPLIANCES.map((a) => [a, pick(APPLIANCE_STATUSES, checklist.appliances?.[a], "unknown")]),
      ) as EquipmentChecklist["appliances"],
      heating: pick(HEATING_STATUSES, checklist.heating, defaults.heating),
      furniture: pick(FURNITURE_STATUSES, checklist.furniture, "not-included"),
      otherItems: typeof checklist.otherItems === "string" ? checklist.otherItems : "",
    },
    buildingExpenses: {
      monthlyCommonCharges:
        typeof expenses.monthlyCommonCharges === "number" ? expenses.monthlyCommonCharges : null,
      includesHeating: expenses.includesHeating === true,
      plannedWorks: typeof expenses.plannedWorks === "string" ? expenses.plannedWorks : "",
    },
    facilities: Array.isArray(r.facilities) ? FACILITIES.filter((x) => (r.facilities as string[]).includes(x)) : [],
    otherFacilities: typeof r.otherFacilities === "string" ? r.otherFacilities : "",
    notes: typeof r.notes === "string" ? r.notes : "",
    photos: Array.isArray(r.photos)
      ? r.photos.map((p) => ({ ...p, area: pick(PHOTO_AREAS, (p as { area?: unknown }).area, "other") }))
      : [],
  };
}

/** Photos grouped by area, in the fixed area order (empty areas omitted). */
export function photosByArea(photos: PropertyInput["photos"]) {
  return PHOTO_AREAS.map((area) => ({ area, photos: photos.filter((p) => p.area === area) })).filter(
    (g) => g.photos.length > 0,
  );
}
