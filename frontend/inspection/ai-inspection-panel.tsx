"use client";

import { useState } from "react";
import { IconSpark } from "@/components/icons";
import { Badge, Button, Card, CardHeader } from "@/components/ui";
import { formatDate, formatEur } from "@/lib/format";
import { PHOTO_AREA_LABELS, type PhotoArea, type Property } from "@/properties/types";
import {
  AiInspectionError,
  API_URL,
  runAiInspection,
  useAiInspections,
  type AiFinding,
  type AiSeverity,
} from "./api";

const SEVERITY: Record<AiSeverity, { label: string; tone: "neutral" | "brand" | "warning" | "negative" }> = {
  info: { label: "Info", tone: "brand" },
  minor: { label: "Minor", tone: "neutral" },
  moderate: { label: "Moderate", tone: "warning" },
  major: { label: "Major", tone: "negative" },
  critical: { label: "Critical", tone: "negative" },
};

const CONDITION_TONE = { poor: "negative", fair: "warning", good: "positive", excellent: "positive", "not-assessable": "neutral" } as const;

/** Photo-based findings from the vision model, run on demand through the backend. */
export function AiInspectionPanel({ property }: { property: Property }) {
  const reports = useAiInspections();
  const report = reports[property.id];
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setRunning(true);
    setError(null);
    try {
      await runAiInspection(property);
    } catch (e) {
      setError(e instanceof AiInspectionError ? e.message : "The inspection failed. Please try again.");
    } finally {
      setRunning(false);
    }
  }

  const photo = (id: string) => property.photos.find((p) => p.id === id);
  const action = API_URL ? (
    <Button onClick={run} disabled={running || property.photos.length === 0} className="no-print">
      <IconSpark /> {running ? "Analysing photos…" : report ? "Run again" : "Run AI inspection"}
    </Button>
  ) : null;

  return (
    <Card>
      <CardHeader
        title="AI Home Inspector"
        description="A vision model examines your photographs for visible defects and missing items"
        action={action}
      />
      <div className="space-y-4 p-5">
        {!API_URL && (
          <p className="text-sm text-muted">
            The AI service is not connected in this preview. Run the backend and ai-service, then set{" "}
            <code className="rounded bg-canvas px-1">NEXT_PUBLIC_API_URL</code> to enable photo analysis.
          </p>
        )}
        {API_URL && property.photos.length === 0 && (
          <p className="text-sm text-muted">Add photographs of each room to the property to run the AI inspection.</p>
        )}
        {API_URL && property.photos.length > 0 && !report && !running && !error && (
          <p className="text-sm text-muted">
            {property.photos.length} photograph{property.photos.length === 1 ? "" : "s"} ready. The analysis takes up to a
            couple of minutes.
          </p>
        )}
        {error && (
          <p role="alert" className="rounded-lg border border-negative/20 bg-negative-soft px-3 py-2 text-sm text-negative">
            {error}
          </p>
        )}

        {report && (
          <>
            <p className="rounded-lg border border-line bg-canvas px-3 py-2 text-xs text-muted">{report.disclaimer}</p>

            {report.areaConditions.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {report.areaConditions.map((c) => (
                  <Badge key={c.area} tone={CONDITION_TONE[c.condition]} title={c.summary}>
                    {PHOTO_AREA_LABELS[c.area as PhotoArea] ?? c.area}: {c.condition.replace("-", " ")}
                  </Badge>
                ))}
              </div>
            )}

            {report.findings.length === 0 ? (
              <p className="text-sm text-muted">No issues were visible in the photographs.</p>
            ) : (
              <ul className="divide-y divide-line rounded-lg ring-1 ring-line">
                {report.findings.map((f) => (
                  <FindingItem key={f.id} f={f} photo={photo} />
                ))}
              </ul>
            )}

            {report.photos.skipped.length > 0 && (
              <div className="text-xs text-muted">
                <p className="font-medium">Not analysed:</p>
                <ul className="mt-1 list-disc pl-5">
                  {report.photos.skipped.map((s) => (
                    <li key={s.photoId}>
                      {photo(s.photoId)?.name ?? s.photoId}: {s.reason}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <p className="text-xs text-subtle">
              {report.photos.analysed} of {report.photos.submitted} photographs analysed by {report.servedBy.join(", ")} on{" "}
              {formatDate(report.generatedAt)}.
            </p>
          </>
        )}
      </div>
    </Card>
  );
}

function FindingItem({ f, photo }: { f: AiFinding; photo: (id: string) => Property["photos"][number] | undefined }) {
  const concern = f.assessment === "potential-concern";
  return (
    <li className="grid gap-3 p-4 sm:grid-cols-[1fr_auto]">
      <div className="min-w-0 space-y-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={SEVERITY[f.severity].tone}>{SEVERITY[f.severity].label}</Badge>
          <Badge tone={concern ? "gold" : "neutral"}>{concern ? "Potential concern" : "Visible in photo"}</Badge>
          <span className="text-xs text-muted">{f.categoryLabel}</span>
          <span className="tabular text-xs text-muted">· confidence {Math.round(f.confidence * 100)}%</span>
        </div>
        <p className="text-sm font-medium">{f.title}</p>
        <p className="text-sm text-muted">{f.description}</p>
        {f.limitation && <p className="text-xs text-subtle italic">{f.limitation}</p>}
        <p className="text-sm">
          <span className="font-medium">Recommendation: </span>
          {f.recommendation}
        </p>
        <p className="text-xs text-muted">
          {f.estimatedCost ? (
            <>
              <span className="tabular font-medium text-text">
                {formatEur(f.estimatedCost.low)} – {formatEur(f.estimatedCost.high)}
              </span>{" "}
              ({f.estimatedCost.covers}){f.costNote ? ` · ${f.costNote}` : ""}
            </>
          ) : (
            f.costNote
          )}
        </p>
      </div>
      <ul className="flex gap-2 sm:flex-col" aria-label="Photographs">
        {f.photoIds.map((id) => {
          const p = photo(id);
          return p ? (
            <li key={id} className="h-16 w-20 overflow-hidden rounded-md ring-1 ring-line">
              {/* eslint-disable-next-line @next/next/no-img-element -- local data URL */}
              <img src={p.dataUrl} alt={`${PHOTO_AREA_LABELS[p.area]}: ${p.name}`} className="h-full w-full object-cover" />
            </li>
          ) : (
            <li key={id} className="text-xs text-subtle">
              Photo {id}
            </li>
          );
        })}
      </ul>
    </li>
  );
}
