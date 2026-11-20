"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent, type ReactNode } from "react";
import { DISTRICTS } from "@/analysis/reference-data";
import { IconSpark } from "@/components/icons";
import { Button, Card, Field } from "@/components/ui";
import { cn } from "@/lib/format";
import { StorageFullError } from "@/lib/local-store";
import { PhotoUpload } from "./photo-upload";
import { createProperty, deleteProperty, runAnalysis } from "./store";
import {
  CONDITIONS,
  CONDITION_LABELS,
  ENERGY_CLASSES,
  HEATING_LABELS,
  HEATING_TYPES,
  PROPERTY_TYPES,
  PROPERTY_TYPE_LABELS,
  type PropertyInput,
} from "./types";

type FormState = Omit<
  PropertyInput,
  "sizeSqm" | "bedrooms" | "bathrooms" | "floor" | "yearBuilt" | "renovatedYear" | "balconySqm" | "askingPrice"
> & {
  sizeSqm: string;
  bedrooms: string;
  bathrooms: string;
  floor: string;
  yearBuilt: string;
  renovatedYear: string;
  balconySqm: string;
  askingPrice: string;
};

const EMPTY: FormState = {
  title: "",
  address: "",
  district: "",
  propertyType: "apartment",
  sizeSqm: "",
  bedrooms: "2",
  bathrooms: "1",
  floor: "1",
  yearBuilt: "",
  renovatedYear: "",
  condition: "good",
  energyClass: "C",
  heating: "autonomous-gas",
  parking: false,
  storage: false,
  elevator: true,
  balconySqm: "0",
  askingPrice: "",
  notes: "",
  photos: [],
};

/** Values for the "Fill with example" button — clearly an example, not a listing. */
const EXAMPLE: FormState = {
  ...EMPTY,
  title: "Example: bright two-bedroom with balcony",
  address: "Example street 5",
  district: "Nea Smyrni",
  sizeSqm: "78",
  floor: "4",
  yearBuilt: "1985",
  renovatedYear: "2017",
  energyClass: "C",
  balconySqm: "12",
  askingPrice: "265000",
  storage: true,
  notes: "Example values for trying the form.",
};

type Errors = Partial<Record<keyof FormState, string>>;

function parseNumber(v: string): number {
  return v.trim() === "" ? NaN : Number(v.replace(/[\s,]/g, ""));
}

function validate(f: FormState, currentYear: number): { errors: Errors; value?: PropertyInput } {
  const e: Errors = {};
  const size = parseNumber(f.sizeSqm);
  const price = parseNumber(f.askingPrice);
  const year = parseNumber(f.yearBuilt);
  const renovated = f.renovatedYear.trim() === "" ? null : parseNumber(f.renovatedYear);
  const floor = parseNumber(f.floor);
  const bedrooms = parseNumber(f.bedrooms);
  const bathrooms = parseNumber(f.bathrooms);
  const balcony = f.balconySqm.trim() === "" ? 0 : parseNumber(f.balconySqm);

  if (f.title.trim().length < 3) e.title = "Give the property a short name (3+ characters).";
  if (!f.district) e.district = "Choose the area.";
  if (!Number.isFinite(size) || size < 15 || size > 2000) e.sizeSqm = "Enter a size between 15 and 2,000 m².";
  if (!Number.isFinite(price) || price < 10_000 || price > 20_000_000)
    e.askingPrice = "Enter an asking price between €10,000 and €20,000,000.";
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

  if (Object.keys(e).length) return { errors: e };
  return {
    errors: e,
    value: {
      title: f.title.trim(),
      address: f.address.trim(),
      district: f.district,
      propertyType: f.propertyType,
      sizeSqm: size,
      bedrooms,
      bathrooms,
      floor,
      yearBuilt: year,
      renovatedYear: renovated,
      condition: f.condition,
      energyClass: f.energyClass,
      heating: f.heating,
      parking: f.parking,
      storage: f.storage,
      elevator: f.elevator,
      balconySqm: balcony,
      askingPrice: price,
      notes: f.notes.trim(),
      photos: f.photos,
    },
  };
}

export function PropertyForm() {
  const router = useRouter();
  const [form, setForm] = useState<FormState>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    if (errors[key]) setErrors((e) => ({ ...e, [key]: undefined }));
  };

  const text = (key: keyof FormState, props: Partial<React.ComponentProps<"input">> = {}) => ({
    id: key,
    name: key,
    className: "input",
    value: form[key] as string,
    onChange: (ev: React.ChangeEvent<HTMLInputElement>) => set(key, ev.target.value as never),
    "aria-invalid": !!errors[key],
    "aria-describedby": errors[key] ? `${key}-error` : undefined,
    ...props,
  });

  function onSubmit(ev: FormEvent) {
    ev.preventDefault();
    setSubmitError(null);
    const { errors: next, value } = validate(form, new Date().getFullYear());
    setErrors(next);
    if (!value) {
      const first = Object.keys(next)[0];
      document.getElementById(first)?.focus();
      return;
    }
    setSubmitting(true);
    let createdId: string | null = null;
    try {
      const property = createProperty(value);
      createdId = property.id;
      runAnalysis(property);
      router.push(`/properties/${property.id}`);
    } catch (err) {
      if (createdId) deleteProperty(createdId);
      setSubmitError(
        err instanceof StorageFullError ? err.message : "Something went wrong while saving. Please try again.",
      );
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-6">
      <Section
        step={1}
        title="The property"
        description="Where it is and what kind of home it is."
        aside={
          <button
            type="button"
            className="text-sm font-medium text-brand-700 hover:underline"
            onClick={() => {
              setForm((f) => ({ ...EXAMPLE, photos: f.photos }));
              setErrors({});
            }}
          >
            Fill with example
          </button>
        }
      >
        <Field
          label="Name"
          htmlFor="title"
          error={errors.title}
          hint="For your own reference"
          className="sm:col-span-2"
        >
          <input {...text("title", { placeholder: "e.g. Two-bed near the metro" })} />
        </Field>
        <Field label="Address" htmlFor="address" hint="Optional — used for Location Intelligence later">
          <input {...text("address", { autoComplete: "street-address" })} />
        </Field>
        <Field label="Area" htmlFor="district" error={errors.district}>
          <select
            id="district"
            className="input"
            value={form.district}
            onChange={(e) => set("district", e.target.value)}
            aria-invalid={!!errors.district}
          >
            <option value="">Select…</option>
            {DISTRICTS.map((d) => (
              <option key={d.name}>{d.name}</option>
            ))}
          </select>
        </Field>
        <Field label="Property type" htmlFor="propertyType">
          <select
            id="propertyType"
            className="input"
            value={form.propertyType}
            onChange={(e) => set("propertyType", e.target.value as FormState["propertyType"])}
          >
            {PROPERTY_TYPES.map((t) => (
              <option key={t} value={t}>
                {PROPERTY_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Asking price (€)" htmlFor="askingPrice" error={errors.askingPrice}>
          <input
            {...text("askingPrice", {
              inputMode: "numeric",
              placeholder: "250000",
            })}
          />
        </Field>
      </Section>

      <Section step={2} title="Size and layout" description="Interior area excludes balconies.">
        <div className="grid grid-cols-2 gap-4 sm:col-span-2 sm:grid-cols-4">
          <Field label="Size (m²)" htmlFor="sizeSqm" error={errors.sizeSqm}>
            <input {...text("sizeSqm", { inputMode: "decimal" })} />
          </Field>
          <Field label="Bedrooms" htmlFor="bedrooms" error={errors.bedrooms}>
            <input {...text("bedrooms", { inputMode: "numeric" })} />
          </Field>
          <Field label="Bathrooms" htmlFor="bathrooms" error={errors.bathrooms}>
            <input {...text("bathrooms", { inputMode: "numeric" })} />
          </Field>
          <Field label="Floor" htmlFor="floor" error={errors.floor} hint="0 = ground">
            <input {...text("floor", { inputMode: "numeric" })} />
          </Field>
          <Field label="Balcony (m²)" htmlFor="balconySqm" error={errors.balconySqm}>
            <input {...text("balconySqm", { inputMode: "decimal" })} />
          </Field>
        </div>
        <fieldset className="sm:col-span-2">
          <legend className="mb-2 text-sm font-medium">Features</legend>
          <div className="flex flex-wrap gap-2">
            {(
              [
                ["elevator", "Elevator"],
                ["parking", "Parking space"],
                ["storage", "Storage room"],
              ] as const
            ).map(([key, label]) => (
              <Toggle key={key} checked={form[key]} onChange={(v) => set(key, v)} label={label} />
            ))}
          </div>
        </fieldset>
      </Section>

      <Section step={3} title="Building and condition" description="Age, condition and energy drive repair estimates.">
        <div className="grid grid-cols-2 gap-4 sm:col-span-2">
          <Field label="Year built" htmlFor="yearBuilt" error={errors.yearBuilt}>
            <input
              {...text("yearBuilt", {
                inputMode: "numeric",
                placeholder: "1980",
              })}
            />
          </Field>
          <Field
            label="Last renovated"
            htmlFor="renovatedYear"
            error={errors.renovatedYear}
            hint="Leave empty if never"
          >
            <input {...text("renovatedYear", { inputMode: "numeric" })} />
          </Field>
        </div>
        <Field label="Condition" htmlFor="condition">
          <select
            id="condition"
            className="input"
            value={form.condition}
            onChange={(e) => set("condition", e.target.value as FormState["condition"])}
          >
            {CONDITIONS.map((c) => (
              <option key={c} value={c}>
                {CONDITION_LABELS[c]}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Heating" htmlFor="heating">
          <select
            id="heating"
            className="input"
            value={form.heating}
            onChange={(e) => set("heating", e.target.value as FormState["heating"])}
          >
            {HEATING_TYPES.map((h) => (
              <option key={h} value={h}>
                {HEATING_LABELS[h]}
              </option>
            ))}
          </select>
        </Field>
        <fieldset className="sm:col-span-2">
          <legend className="mb-2 text-sm font-medium">Energy class</legend>
          <div className="flex flex-wrap gap-1.5" role="radiogroup">
            {ENERGY_CLASSES.map((c) => (
              <button
                key={c}
                type="button"
                role="radio"
                aria-checked={form.energyClass === c}
                onClick={() => set("energyClass", c)}
                className={cn(
                  "h-9 min-w-11 rounded-lg border px-2.5 text-sm font-semibold transition-colors",
                  form.energyClass === c
                    ? "border-ink-900 bg-ink-900 text-white"
                    : "border-line-strong bg-surface text-muted hover:text-text",
                )}
              >
                {c}
              </button>
            ))}
          </div>
        </fieldset>
      </Section>

      <Section
        step={4}
        title="Photographs"
        description="Photos are stored downscaled in this browser. The AI Home Inspector will analyse them in a later phase."
      >
        <div className="sm:col-span-2">
          <PhotoUpload photos={form.photos} onChange={(photos) => set("photos", photos)} />
        </div>
        <Field label="Notes" htmlFor="notes" hint="Anything the listing or agent mentioned" className="sm:col-span-2">
          <textarea
            id="notes"
            className="input min-h-24"
            value={form.notes}
            onChange={(e) => set("notes", e.target.value)}
            maxLength={2000}
          />
        </Field>
      </Section>

      {submitError && (
        <p
          role="alert"
          className="rounded-lg border border-negative/20 bg-negative-soft px-4 py-3 text-sm text-negative"
        >
          {submitError}
        </p>
      )}
      {Object.values(errors).some(Boolean) && (
        <p role="alert" className="text-sm text-negative">
          Please correct the highlighted fields.
        </p>
      )}

      <div className="flex flex-col-reverse gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-xs text-subtle">The preview runs a rule-based sample engine in your browser.</p>
        <Button type="submit" size="lg" disabled={submitting}>
          <IconSpark /> {submitting ? "Analysing…" : "Run analysis"}
        </Button>
      </div>
    </form>
  );
}

function Section({
  step,
  title,
  description,
  aside,
  children,
}: {
  step: number;
  title: string;
  description: string;
  aside?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Card>
      <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
        <div className="flex gap-3">
          <span className="tabular flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-ink-900 text-xs font-semibold text-white">
            {step}
          </span>
          <div>
            <h2 className="text-sm font-semibold">{title}</h2>
            <p className="mt-0.5 text-sm text-muted">{description}</p>
          </div>
        </div>
        {aside}
      </div>
      <div className="grid gap-4 p-5 sm:grid-cols-2">{children}</div>
    </Card>
  );
}

function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={cn(
        "inline-flex h-9 items-center gap-2 rounded-lg border px-3 text-sm transition-colors",
        checked
          ? "border-brand-500 bg-brand-50 font-medium text-brand-700"
          : "border-line-strong bg-surface text-muted",
      )}
    >
      <span className={cn("h-2 w-2 rounded-full", checked ? "bg-brand-500" : "bg-line-strong")} />
      {label}
    </button>
  );
}
