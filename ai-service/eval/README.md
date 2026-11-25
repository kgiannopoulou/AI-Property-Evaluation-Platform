# AI accuracy evaluation

Measures how well the AI Home Inspector finds what a person sees in property photographs, so a
model or prompt change can be gated on numbers instead of impressions.

```bash
npm run eval -- path/to/dataset --min-precision 0.8 --min-recall 0.7 --out results.json
```

It runs the **real** vision model (needs `ANTHROPIC_API_KEY`, costs API credits). Exit code 0 means
it passed, 1 means the gate failed and 2 means a setup problem. The scorer itself (`src/evaluation`)
is unit-tested in CI with hand-made reports (`test/evaluation.test.ts`).

## Dataset

No dataset is committed. Photographs of real homes need the owner's permission and must not end up
in a public repository. Keep the set outside the repo (or in private storage) with this layout:

```
dataset/
├── manifest.json
└── flat-1/
    ├── k1.jpg
    └── b1.jpg
```

`manifest.json` is an array of labelled cases. This example shows the format; it describes no
real photographs:

```json
[
  {
    "caseId": "flat-1",
    "labelledBy": "surveyor site visit, date",
    "photos": [
      { "photoId": "k1", "file": "flat-1/k1.jpg", "area": "kitchen" },
      { "photoId": "b1", "file": "flat-1/b1.jpg", "area": "bathroom" }
    ],
    "expected": {
      "findings": [
        { "category": "mould-damp", "photoIds": ["b1"], "severity": "major" },
        { "category": "kitchen-condition", "severity": "minor" }
      ],
      "absent": ["crack"],
      "areaConditions": [{ "area": "bathroom", "condition": "poor" }]
    }
  }
]
```

Label with the evidence a site visit or survey gave, not only with what the photo seems to show.
Then the evaluation also measures what the model cannot see.

## Metrics

| Metric | Meaning |
| --- | --- |
| Precision | Share of reported findings that a labeller confirmed |
| Recall | Share of labelled findings the model reported (same category, at least one of the labelled photos) |
| False alarms | Findings in a category the labeller marked as *absent*, e.g. a shadow that looks like a crack |
| Severity agreement | Matched findings whose severity is within one step of the label |
| Condition agreement | Areas whose condition rating is within one step of the label |
| Failed cases | Inspections that errored. They count every expected finding as missed |

A summary with no evidence (no findings at all) fails the gate. Start with at least 30 labelled
properties across all areas before trusting the numbers. Raise the thresholds as the set grows.
