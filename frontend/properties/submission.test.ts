import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  EMPTY_FORM,
  EXAMPLE_FORM,
  LIMITS,
  normalizeProperty,
  parseCoordinates,
  photosByArea,
  validateSubmission,
  type FormState,
} from "./submission.ts";

const YEAR = 2026;
const form = (overrides: Partial<FormState> = {}): FormState => ({ ...EXAMPLE_FORM, ...overrides });

describe("parseCoordinates", () => {
  it("accepts the 'lat, lon' format map apps copy", () => {
    assert.deepEqual(parseCoordinates("37.9755, 23.7348"), { lat: 37.9755, lon: 23.7348 });
    assert.deepEqual(parseCoordinates(" 37.9755 23.7348 "), { lat: 37.9755, lon: 23.7348 });
  });
  it("treats empty as no location", () => assert.equal(parseCoordinates("  "), null));
  it("rejects malformed or out-of-range input", () => {
    assert.equal(parseCoordinates("Athens"), undefined);
    assert.equal(parseCoordinates("95, 23"), undefined);
    assert.equal(parseCoordinates("37.9, 190"), undefined);
  });
  it("rounds to 6 decimals (~10 cm)", () =>
    assert.deepEqual(parseCoordinates("37.123456789, 23.987654321"), { lat: 37.123457, lon: 23.987654 }));
});

describe("validateSubmission", () => {
  it("accepts the example and returns a typed property", () => {
    const { errors, value } = validateSubmission(EXAMPLE_FORM, YEAR);
    assert.deepEqual(errors, {});
    assert.ok(value);
    assert.equal(value.listingType, "sale");
    assert.equal(value.askingPrice, 265000);
    assert.deepEqual(value.location, { lat: 37.9455, lon: 23.7135 });
    assert.equal(value.buildingExpenses.monthlyCommonCharges, 45);
    assert.equal(value.checklist.appliances.dishwasher, "missing");
    assert.equal(value.checklist.furniture, "partial");
  });

  it("reports every missing required field on an empty form", () => {
    const { errors, value } = validateSubmission(EMPTY_FORM, YEAR);
    assert.equal(value, undefined);
    for (const key of ["title", "district", "sizeSqm", "askingPrice", "yearBuilt"] as const)
      assert.ok(errors[key], `expected an error for ${key}`);
  });

  it("uses monthly-rent limits for rentals", () => {
    assert.ok(validateSubmission(form({ listingType: "sale", askingPrice: "900" }), YEAR).errors.askingPrice);
    const rent = validateSubmission(form({ listingType: "rent", askingPrice: "900" }), YEAR);
    assert.deepEqual(rent.errors, {});
    assert.equal(rent.value?.askingPrice, 900);
    assert.match(
      validateSubmission(form({ listingType: "rent", askingPrice: "265000" }), YEAR).errors.askingPrice ?? "",
      /monthly rent/,
    );
  });

  it("accepts thousands separators in amounts", () =>
    assert.equal(validateSubmission(form({ askingPrice: "265,000" }), YEAR).value?.askingPrice, 265000));

  it("rejects a renovation before the build year or in the future", () => {
    assert.ok(validateSubmission(form({ yearBuilt: "1990", renovatedYear: "1980" }), YEAR).errors.renovatedYear);
    assert.ok(validateSubmission(form({ renovatedYear: String(YEAR + 1) }), YEAR).errors.renovatedYear);
  });

  it("validates optional fields only when filled", () => {
    assert.equal(validateSubmission(form({ coordinates: "" }), YEAR).value?.location, null);
    assert.ok(validateSubmission(form({ coordinates: "somewhere" }), YEAR).errors.coordinates);
    assert.equal(validateSubmission(form({ commonCharges: "" }), YEAR).value?.buildingExpenses.monthlyCommonCharges, null);
    assert.ok(validateSubmission(form({ commonCharges: "-5" }), YEAR).errors.commonCharges);
  });

  it("drops the certificate number unless a certificate is issued", () => {
    const v = validateSubmission(form({ energyCertificate: { status: "none", number: "123" } }), YEAR).value;
    assert.equal(v?.energyCertificate.number, "");
  });

  it("caps the number of photographs", () => {
    const photos = Array.from({ length: LIMITS.maxPhotos + 1 }, (_, i) => ({
      id: String(i),
      name: `${i}.jpg`,
      area: "kitchen" as const,
      dataUrl: "data:,",
    }));
    assert.ok(validateSubmission(form({ photos }), YEAR).errors.photos);
  });

  it("keeps facilities in a stable order and trims text", () => {
    const v = validateSubmission(form({ facilities: ["pool", "garden"], otherFacilities: "  sauna " }), YEAR).value;
    assert.deepEqual(v?.facilities, ["garden", "pool"]);
    assert.equal(v?.otherFacilities, "sauna");
  });
});

describe("normalizeProperty", () => {
  // Shape of a record saved by the Phase 3 website.
  const phase3 = {
    id: "p1",
    createdAt: "2026-11-01T00:00:00.000Z",
    isSample: false,
    title: "Old record",
    address: "",
    district: "Pagrati",
    propertyType: "apartment",
    sizeSqm: 70,
    bedrooms: 2,
    bathrooms: 1,
    floor: 2,
    yearBuilt: 1980,
    renovatedYear: null,
    condition: "good",
    energyClass: "C",
    heating: "autonomous-gas",
    parking: false,
    storage: false,
    elevator: true,
    balconySqm: 5,
    askingPrice: 200000,
    notes: "",
    photos: [{ id: "x", name: "a.jpg", dataUrl: "data:," }],
  };

  it("upgrades a Phase 3 record with neutral defaults", () => {
    const p = normalizeProperty(phase3);
    assert.equal(p.listingType, "sale");
    assert.equal(p.location, null);
    assert.equal(p.constructionType, "unknown");
    assert.equal(p.energyCertificate.status, "unknown");
    assert.equal(p.cooling, "none");
    assert.deepEqual(Object.values(p.checklist.appliances), ["unknown", "unknown", "unknown", "unknown", "unknown"]);
    assert.equal(p.checklist.heating, "unknown");
    assert.equal(p.buildingExpenses.monthlyCommonCharges, null);
    assert.deepEqual(p.facilities, []);
    assert.equal(p.photos[0].area, "other");
    // Existing values are kept.
    assert.equal(p.askingPrice, 200000);
    assert.equal(p.district, "Pagrati");
  });

  it("is idempotent on current records", () => {
    const once = normalizeProperty(phase3);
    assert.deepEqual(normalizeProperty(once), once);
  });

  it("replaces values that are no longer allowed", () => {
    const p = normalizeProperty({ ...phase3, listingType: "auction", facilities: ["pool", "moat"] });
    assert.equal(p.listingType, "sale");
    assert.deepEqual(p.facilities, ["pool"]);
  });
});

describe("photosByArea", () => {
  it("groups in the fixed area order and skips empty areas", () => {
    const mk = (id: string, area: "kitchen" | "exterior" | "bathroom") => ({ id, name: id, area, dataUrl: "" });
    const groups = photosByArea([mk("1", "exterior"), mk("2", "kitchen"), mk("3", "exterior"), mk("4", "bathroom")]);
    assert.deepEqual(
      groups.map((g) => [g.area, g.photos.length]),
      [
        ["kitchen", 1],
        ["bathroom", 1],
        ["exterior", 2],
      ],
    );
  });
});
