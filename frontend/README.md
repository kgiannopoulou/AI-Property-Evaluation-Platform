# Frontend

Next.js 16 (App Router, Cache Components) + React 19 + Tailwind CSS 4.

```bash
npm install
npm run dev     # http://localhost:3000
npm run lint
npm run build
```

- `app/` routes. Public pages (`/`, `/pricing`, `/login`, `/register`) use the marketing header; signed-in pages live in the `(app)` route group and share the sidebar shell.
- `analysis/sample-engine.ts` is the Phase 3 stand-in for the Java valuation service and the AI service. It is deterministic and rule-based, and uses the **illustrative** values in `analysis/reference-data.ts`.
- `properties/store.ts` keeps properties and analyses in `localStorage`; Phase 4 replaces it with API calls.
- Charts are dependency-free SVG/CSS (`components/charts.tsx`).

See the [root README](../README.md) for the full architecture and roadmap.
