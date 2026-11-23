"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent, type ReactNode } from "react";
import { DISTRICTS } from "@/analysis/reference-data";
import { IconSpark } from "@/components/icons";
import { Button, Card, Field } from "@/components/ui";
import { cn } from "@/lib/format";
import { StorageFullError } from "@/lib/local-store";
import { PhotoUploadByArea } from "./photo-upload";
import { createProperty, deleteProperty, runAnalysis } from "./store";
import {
  EMPTY_FORM,
  EXAMPLE_FORM,
  formatCoordinates,
  validateSubmission,
  type FormErrors,
  type FormState,
} from "./submission";
import {
  APPLIANCES,
  APPLIANCE_LABELS,
  APPLIANCE_STATUSES,
  APPLIANCE_STATUS_LABELS,
  CERTIFICATE_LABELS,
  CERTIFICATE_STATUSES,
  CONDITIONS,
  CONDITION_LABELS,
  CONSTRUCTION_LABELS,
  CONSTRUCTION_TYPES,
  COOLING_LABELS,
  COOLING_TYPES,
  ENERGY_CLASSES,
  FACILITIES,
  FACILITY_LABELS,
  FURNITURE_STATUSES,
  FURNITURE_STATUS_LABELS,
  HEATING_LABELS,
  HEATING_STATUSES,
  HEATING_STATUS_LABELS,
  HEATING_TYPES,
  LISTING_TYPES,
  PROPERTY_TYPES,
  PROPERTY_TYPE_LABELS,
  type ApplianceStatus,
} from "./types";

const LISTING_CHOICE_LABELS = { sale: "Purchase", rent: "Rental" } as const;

export function PropertyForm() {
  const router = useRouter();
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [errors, setErrors] = useState<FormErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [locating, setLocating] = useState(false);

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    if (errors[key]) setErrors((e) => ({ ...e, [key]: undefined }));
  };
  const setChecklist = (patch: Partial<FormState["checklist"]>) =>
    setForm((f) => ({ ...f, checklist: { ...f.checklist, ...patch } }));
  const setAppliance = (key: (typeof APPLIANCES)[number], status: ApplianceStatus) =>
    setForm((f) => ({
      ...f,
      checklist: { ...f.checklist, appliances: { ...f.checklist.appliances, [key]: status } },
    }));

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

  const rent = form.listingType === "rent";

  function useMyLocation() {
    if (!("geolocation" in navigator)) return;
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        set("coordinates", formatCoordinates({ lat: +pos.coords.latitude.toFixed(6), lon: +pos.coords.longitude.toFixed(6) }));
        setLocating(false);
      },
      () => {
        setErrors((e) => ({ ...e, coordinates: "Location permission was refused — paste the coordinates instead." }));
        setLocating(false);
      },
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  }

  function onSubmit(ev: FormEvent) {
    ev.preventDefault();
    setSubmitError(null);
    const { errors: next, value } = validateSubmission(form, new Date().getFullYear());
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
        title="Listing and location"
        description="Purchase or rental, where it is and what it costs."
        aside={
          <button
            type="button"
            className="text-sm font-medium whitespace-nowrap text-brand-700 hover:underline"
            onClick={() => {
              setForm((f) => ({ ...EXAMPLE_FORM, photos: f.photos }));
              setErrors({});
            }}
          >
            Fill with example
          </button>
        }
      >
        <fieldset className="sm:col-span-2">
          <legend className="mb-2 text-sm font-medium">Are you buying or renting?</legend>
          <Segmented
            name="listingType"
            options={LISTING_TYPES.map((v) => [v, LISTING_CHOICE_LABELS[v]])}
            value={form.listingType}
            onChange={(v) => set("listingType", v)}
          />
        </fieldset>
        <Field label="Name" htmlFor="title" error={errors.title} hint="For your own reference" className="sm:col-span-2">
          <input {...text("title", { placeholder: "e.g. Two-bed near the metro" })} />
        </Field>
        <Field label="Address" htmlFor="address" hint="Street and number">
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
        <Field
          label="Map location"
          htmlFor="coordinates"
          error={errors.coordinates}
          hint="Optional · latitude, longitude — copy from a map app. Used to find pharmacies, transport and schools nearby."
          className="sm:col-span-2"
        >
          <div className="flex gap-2">
            <input {...text("coordinates", { placeholder: "37.9755, 23.7348", inputMode: "decimal" })} />
            <Button type="button" variant="secondary" onClick={useMyLocation} disabled={locating}>
              {locating ? "Locating…" : "I'm there now"}
            </Button>
          </div>
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
        <Field label={rent ? "Asking rent (€ per month)" : "Asking price (€)"} htmlFor="askingPrice" error={errors.askingPrice}>
          <input {...text("askingPrice", { inputMode: "numeric", placeholder: rent ? "950" : "250000" })} />
        </Field>
      </Section>

      <Section step={2} title="Size and layout" description="Interior area excludes balconies.">
        <div className="grid grid-cols-2 gap-4 sm:col-span-2 sm:grid-cols-5">
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
          <Field label="Balcony (m²)" htmlFor="balconySqm" error={errors.balconySqm} hint="0 = none">
            <input {...text("balconySqm", { inputMode: "decimal" })} />
          </Field>
        </div>
        <fieldset className="sm:col-span-2">
          <legend className="mb-2 text-sm font-medium">Parking, elevator and storage</legend>
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

      <Section step={3} title="Building and condition" description="Age, construction and condition drive repair estimates.">
        <div className="grid grid-cols-2 gap-4 sm:col-span-2">
          <Field label="Year built" htmlFor="yearBuilt" error={errors.yearBuilt}>
            <input {...text("yearBuilt", { inputMode: "numeric", placeholder: "1980" })} />
          </Field>
          <Field label="Last renovated" htmlFor="renovatedYear" error={errors.renovatedYear} hint="Leave empty if never">
            <input {...text("renovatedYear", { inputMode: "numeric" })} />
          </Field>
        </div>
        <Field label="Construction type" htmlFor="constructionType">
          <select
            id="constructionType"
            className="input"
            value={form.constructionType}
            onChange={(e) => set("constructionType", e.target.value as FormState["constructionType"])}
          >
            {CONSTRUCTION_TYPES.map((c) => (
              <option key={c} value={c}>
                {CONSTRUCTION_LABELS[c]}
              </option>
            ))}
          </select>
        </Field>
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
      </Section>

      <Section step={4} title="Energy, heating and cooling" description="The energy certificate is required to sell or let.">
        <Field label="Energy certificate" htmlFor="certificateStatus">
          <select
            id="certificateStatus"
            className="input"
            value={form.energyCertificate.status}
            onChange={(e) =>
              set("energyCertificate", {
                ...form.energyCertificate,
                status: e.target.value as FormState["energyCertificate"]["status"],
              })
            }
          >
            {CERTIFICATE_STATUSES.map((s) => (
              <option key={s} value={s}>
                {CERTIFICATE_LABELS[s]}
              </option>
            ))}
          </select>
        </Field>
        {form.energyCertificate.status === "issued" ? (
          <Field label="Certificate number" htmlFor="energyCertificate" error={errors.energyCertificate} hint="Optional">
            <input
              id="energyCertificate"
              className="input"
              value={form.energyCertificate.number}
              maxLength={40}
              onChange={(e) => set("energyCertificate", { ...form.energyCertificate, number: e.target.value })}
            />
          </Field>
        ) : (
          <div className="hidden sm:block" />
        )}
        <fieldset className="sm:col-span-2">
          <legend className="mb-2 text-sm font-medium">
            {form.energyCertificate.status === "issued" ? "Energy rating" : "Estimated energy rating"}
          </legend>
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
        <Field label="Heating system" htmlFor="heating">
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
        <Field label="Cooling" htmlFor="cooling">
          <select
            id="cooling"
            className="input"
            value={form.cooling}
            onChange={(e) => set("cooling", e.target.value as FormState["cooling"])}
          >
            {COOLING_TYPES.map((c) => (
              <option key={c} value={c}>
                {COOLING_LABELS[c]}
              </option>
            ))}
          </select>
        </Field>
      </Section>

      <Section
        step={5}
        title="Furniture and appliances"
        description="What comes with the property, and whether it works."
      >
        <div className="sm:col-span-2">
          <table className="w-full text-sm">
            <caption className="sr-only">Appliance checklist</caption>
            <thead className="sr-only sm:not-sr-only">
              <tr className="text-left text-xs text-muted">
                <th className="pb-2 font-medium">Item</th>
                <th className="pb-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {APPLIANCES.map((a) => (
                <ChecklistRow
                  key={a}
                  label={APPLIANCE_LABELS[a]}
                  name={`appliance-${a}`}
                  options={APPLIANCE_STATUSES.map((s) => [s, APPLIANCE_STATUS_LABELS[s]])}
                  value={form.checklist.appliances[a]}
                  onChange={(s) => setAppliance(a, s)}
                />
              ))}
              <ChecklistRow
                label="Heating"
                name="checklist-heating"
                options={HEATING_STATUSES.map((s) => [s, HEATING_STATUS_LABELS[s]])}
                value={form.checklist.heating}
                onChange={(s) => setChecklist({ heating: s })}
              />
              <ChecklistRow
                label="Furniture"
                name="checklist-furniture"
                options={FURNITURE_STATUSES.map((s) => [s, FURNITURE_STATUS_LABELS[s]])}
                value={form.checklist.furniture}
                onChange={(s) => setChecklist({ furniture: s })}
              />
            </tbody>
          </table>
        </div>
        <Field
          label="Other furniture and appliances"
          htmlFor="otherItems"
          hint="e.g. built-in wardrobes, TV, microwave, water heater"
          className="sm:col-span-2"
        >
          <input
            id="otherItems"
            className="input"
            value={form.checklist.otherItems}
            maxLength={500}
            onChange={(e) => setChecklist({ otherItems: e.target.value })}
          />
        </Field>
      </Section>

      <Section step={6} title="Building expenses and facilities" description="Running costs of the building and what else is included.">
        <Field
          label="Common charges (€ per month)"
          htmlFor="commonCharges"
          error={errors.commonCharges}
          hint="Building maintenance, cleaning, elevator · leave empty if not known"
        >
          <input {...text("commonCharges", { inputMode: "decimal", placeholder: "50" })} />
        </Field>
        <div className="flex items-start sm:pt-7">
          <Toggle
            checked={form.commonChargesIncludeHeating}
            onChange={(v) => set("commonChargesIncludeHeating", v)}
            label="Includes central heating"
          />
        </div>
        <Field
          label="Planned building works or special levies"
          htmlFor="plannedWorks"
          hint="e.g. facade repair voted for next year, new elevator"
          className="sm:col-span-2"
        >
          <input {...text("plannedWorks", { maxLength: 500 })} />
        </Field>
        <fieldset className="sm:col-span-2">
          <legend className="mb-2 text-sm font-medium">Included facilities</legend>
          <div className="flex flex-wrap gap-2">
            {FACILITIES.map((f) => (
              <Toggle
                key={f}
                checked={form.facilities.includes(f)}
                onChange={(on) =>
                  set("facilities", on ? [...form.facilities, f] : form.facilities.filter((x) => x !== f))
                }
                label={FACILITY_LABELS[f]}
              />
            ))}
          </div>
        </fieldset>
        <Field label="Other facilities" htmlFor="otherFacilities" className="sm:col-span-2">
          <input {...text("otherFacilities", { maxLength: 500, placeholder: "e.g. shared laundry, bicycle storage" })} />
        </Field>
      </Section>

      <Section
        step={7}
        title="Photographs"
        description="Add photos for each area. They are stored downscaled in this browser; the AI Home Inspector examines them for visible defects from the report page."
      >
        <div className="sm:col-span-2">
          <PhotoUploadByArea photos={form.photos} onChange={(photos) => set("photos", photos)} />
          {errors.photos && <p className="mt-2 text-xs text-negative">{errors.photos}</p>}
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
        <p role="alert" className="rounded-lg border border-negative/20 bg-negative-soft px-4 py-3 text-sm text-negative">
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
        checked ? "border-brand-500 bg-brand-50 font-medium text-brand-700" : "border-line-strong bg-surface text-muted",
      )}
    >
      <span className={cn("h-2 w-2 rounded-full", checked ? "bg-brand-500" : "bg-line-strong")} />
      {label}
    </button>
  );
}

/** Radio buttons styled as a segmented control; arrow keys move between options natively. */
function Segmented<T extends string>({
  name,
  options,
  value,
  onChange,
  size = "md",
  label,
}: {
  name: string;
  options: [T, string][];
  value: T;
  onChange: (v: T) => void;
  size?: "sm" | "md";
  label?: string;
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex flex-wrap gap-1 rounded-lg bg-canvas p-1 ring-1 ring-line">
      {options.map(([v, text]) => (
        <label
          key={v}
          className={cn(
            "cursor-pointer rounded-md text-center transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-brand-500",
            size === "sm" ? "px-2.5 py-1 text-xs" : "px-4 py-1.5 text-sm",
            value === v ? "bg-surface font-medium text-text shadow-sm ring-1 ring-line" : "text-muted hover:text-text",
          )}
        >
          <input
            type="radio"
            name={name}
            value={v}
            checked={value === v}
            onChange={() => onChange(v)}
            className="sr-only"
          />
          {text}
        </label>
      ))}
    </div>
  );
}

function ChecklistRow<T extends string>({
  label,
  name,
  options,
  value,
  onChange,
}: {
  label: string;
  name: string;
  options: [T, string][];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <tr className="flex flex-col gap-1.5 py-2.5 sm:table-row">
      <th scope="row" className="pr-4 text-left font-medium sm:py-2.5">
        {label}
      </th>
      <td className="sm:py-2.5">
        <Segmented name={name} options={options} value={value} onChange={onChange} size="sm" label={label} />
      </td>
    </tr>
  );
}
