/**
 * Contracts shared by the AI modules. The Java backend calls this service; the
 * browser never talks to it directly, so AI provider keys stay server-side.
 */
export type PhotoRef = {
  /** Object-storage key (Supabase Storage or compatible), never a public URL with a key in it. */
  storageKey: string;
  room?: string;
};

export type Severity = "info" | "low" | "medium" | "high";

export type VisualFinding = {
  photo: string;
  label: string;
  severity: Severity;
  /** 0–1 model confidence. */
  confidence: number;
};

export type InspectionRequest = {
  propertyId: string;
  photos: PhotoRef[];
};

export type InspectionResponse = {
  propertyId: string;
  defects: VisualFinding[];
  missingEquipment: string[];
  model: string;
};
