/**
 * Appliances and furniture: what to expect in each area, and what the owner
 * declared. The model compares the photos with this; a missing item is only
 * reported when the photos of that area show where it would be.
 */

export const CHECKLIST_ITEMS = [
  "refrigerator",
  "washing-machine",
  "dishwasher",
  "oven-stove",
  "air-conditioning",
  "heating",
  "furniture",
] as const;
export type ChecklistItem = (typeof CHECKLIST_ITEMS)[number];

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

/** Where each checklist item would normally be visible. */
export const EXPECTED_IN_AREA: Record<ChecklistItem, PhotoArea[]> = {
  refrigerator: ["kitchen"],
  "washing-machine": ["kitchen", "bathroom", "other"],
  dishwasher: ["kitchen"],
  "oven-stove": ["kitchen"],
  "air-conditioning": ["living-room", "bedroom"],
  heating: ["living-room", "bedroom", "kitchen", "bathroom"],
  furniture: ["living-room", "bedroom"],
};

export type DeclaredChecklist = Partial<Record<ChecklistItem, string>>;

/** Lines for the prompt: what the owner says is included, limited to areas that were photographed. */
export function describeDeclaredEquipment(declared: DeclaredChecklist | undefined, areas: Set<PhotoArea>): string {
  if (!declared) return "The owner did not submit an appliance checklist.";
  const lines = CHECKLIST_ITEMS.filter((item) => declared[item]).map((item) => {
    const photographed = EXPECTED_IN_AREA[item].filter((a) => areas.has(a));
    const where = photographed.length
      ? `would be visible in: ${photographed.join(", ")}`
      : "its usual area was not photographed, so do not report it as missing";
    return `- ${item}: declared "${declared[item]}" (${where})`;
  });
  return lines.length ? lines.join("\n") : "The owner did not submit an appliance checklist.";
}
