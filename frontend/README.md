# Frontend

Next.js 16 (App Router, Cache Components) + React 19 + Tailwind CSS 4.

```bash
npm install
npm run dev     # http://localhost:3000
npm run lint
npm test        # node:test unit tests, no extra dependencies
npm run build
```

- `app/` routes. Public pages (`/`, `/pricing`, `/login`, `/register`) use the marketing header; signed-in pages live in the `(app)` route group and share the sidebar shell.
- `properties/submission.ts` holds the property submission rules: form state, validation, coordinate parsing and the upgrade of records saved by older versions. It has no React or browser dependencies, so it is unit-tested with Node's built-in test runner (`*.test.ts`, run with native TypeScript type stripping).
- `analysis/sample-engine.ts` is the stand-in for the Java valuation service and the AI service. It is deterministic and rule-based, and uses the **illustrative** values in `analysis/reference-data.ts`.
- `properties/store.ts` keeps properties and analyses in `localStorage`; the backend API will replace it.
- `inspection/` is the AI Home Inspector client: it posts the property's photos to the backend (`NEXT_PUBLIC_API_URL`, set in `.env.local`) and shows the findings. Without that variable the panel explains how to connect it.
- Charts are dependency-free SVG/CSS (`components/charts.tsx`).

See the [root README](../README.md) for the full architecture and roadmap.
