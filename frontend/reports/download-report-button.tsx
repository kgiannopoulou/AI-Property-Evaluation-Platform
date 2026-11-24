"use client";

import { useState } from "react";
import type { InspectionFinding } from "@/analysis/types";
import { IconDownload } from "@/components/icons";
import { Button } from "@/components/ui";
import { useAiInspections } from "@/inspection/api";
import { API_URL, ApiError, postRaw } from "@/lib/api";
import { usePreferences } from "@/lib/session";
import type { Property } from "@/properties/types";
import { useRepairQuotes } from "@/valuation/api";
import { buildReportRequest } from "./request";

/** Downloads the backend PDF report (all modules run server-side). Hidden when no backend is connected. */
export function DownloadReportButton({ property, ruleFindings }: { property: Property; ruleFindings: InspectionFinding[] }) {
  const ai = useAiInspections()[property.id];
  const quotes = useRepairQuotes()[property.id];
  const prefs = usePreferences();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!API_URL) return null;

  async function download() {
    setBusy(true);
    setError(null);
    try {
      const request = buildReportRequest(property, { aiFindings: ai?.findings, ruleFindings, repairQuotes: quotes }, prefs);
      const res = await postRaw("/api/v1/reports/pdf", request, "The report");
      const url = URL.createObjectURL(await res.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = `${property.title.replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "").slice(0, 60) || "property"}-report.pdf`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The report could not be created.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button variant="secondary" onClick={download} disabled={busy} title={error ?? "PDF from the backend engines"}>
        <IconDownload /> {busy ? "Creating PDF…" : "PDF report"}
      </Button>
      {error && (
        <span role="alert" className="self-center text-xs text-negative">
          {error}
        </span>
      )}
    </>
  );
}
