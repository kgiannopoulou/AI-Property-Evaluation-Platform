import type { Metadata } from "next";
import { SavedProperties } from "./saved-properties";

export const metadata: Metadata = { title: "Saved properties" };

export default function PropertiesPage() {
  return <SavedProperties />;
}
