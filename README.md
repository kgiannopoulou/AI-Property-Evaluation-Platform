# AI Property Evaluation Platform

An independent second opinion on a property **before you make an offer**: valuation range, asking-price verdict,
visible defects, ownership and renovation costs, investment returns and a negotiation range you can defend, all in one report.

> **Status: Phase 3 of the roadmap (website design with sample results).** The website runs end to end in the
> browser using a rule-based **sample engine** with illustrative reference prices. It is not market data, does no
> image analysis and is not a valuation. Every sample result is labelled as such in the interface.

## Architecture

| Part | Technology | Responsibility |
| --- | --- | --- |
| [`frontend/`](frontend) | TypeScript · React 19 · Next.js 16 · Tailwind CSS 4 | Website, dashboard, property forms, photo uploads, charts and reports |
| [`backend/`](backend) | Java 21 · Spring Boot 4 | Accounts, properties, permissions, subscriptions, calculations, comparisons and business rules |
| [`ai-service/`](ai-service) | Node.js · TypeScript | Image analysis, visual defect and equipment detection, AI report text, AI model calls |
| [`database/`](database) | PostgreSQL · PostGIS | Users, properties, prices, analysis results, geography and saved comparisons |

```
Browser ──► frontend (Next.js) ──► backend (Spring Boot) ──► PostgreSQL + PostGIS
                                         │
                                         └──► ai-service (Node) ──► AI vision API
```

The three applications talk over HTTP APIs. **The browser never receives secret AI, storage or payment keys**:
only the backend and AI service read them, from environment variables (see [`.env.example`](.env.example)).

Supporting services planned for later phases: Supabase Storage (or compatible object storage) for photos and
documents, Google Maps or Mapbox for location intelligence, Stripe or a local provider for subscriptions, an
AI vision API for photo analysis, and Vercel plus a Java/Node host for deployment.

## Repository layout

```
├── frontend/
│   ├── app/                 routes: home, login, register, pricing, (app)/dashboard, properties, compare, settings
│   ├── components/          app shell, UI kit, dependency-free SVG charts, icons
│   ├── properties/          property types, form, photo upload, browser store, sample records
│   ├── analysis/            sample analysis engine and illustrative reference data
│   └── lib/                 formatting, session, local storage, module list
├── backend/src/main/java/com/propertyapp/
│   └── users · properties · valuation · financial · renovation · location · subscriptions · reports · common
├── ai-service/src/
│   └── image-analysis · defect-detection · equipment-detection · report-generation
├── database/migrations/     V1__initial_schema.sql (PostGIS)
├── compose.yaml             local PostGIS database
└── .github/workflows/ci.yml lint, build and tests for every part + migration check
```

## What the website contains now

| Page | Route | What works |
| --- | --- | --- |
| Home | `/` | Product overview, how it works, module list |
| Registration and login | `/register`, `/login` | Form validation; prototype session stored in the browser (no real auth yet) |
| Dashboard | `/dashboard` | Recent analyses, portfolio stats, plan usage, module status |
| Add a property | `/properties/new` | Validated form, energy class picker, feature toggles, photo upload (drag and drop, downscaled in the browser) |
| Analysis results | `/properties/[id]` | Valuation range and verdict, scores, value factors, market comparator, inspection findings, financial and investment calculator, renovation scenarios, negotiation assistant, printable report |
| Saved properties | `/properties` | Search, sort, delete, select up to four to compare |
| Property comparison | `/compare` | Side-by-side table with the best value in each row highlighted |
| Pricing | `/pricing` | Free, Premium, Professional and Business plans, monthly/annual toggle |
| Profile and settings | `/settings` | Profile, mortgage assumptions used by the calculator, notifications, reset demo data |

Data lives in `localStorage` in this phase. The store hooks have the same shape the API client will have, so
Phase 4 swaps the transport without changing the pages.

## Running locally

Requirements: Node.js 22.18+ (24 LTS recommended), Java 21, and optionally Docker for the database.

```bash
# Website: http://localhost:3000
cd frontend && npm install && npm run dev

# Backend: http://localhost:8080/api/v1/status
cd backend && ./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run

# AI service: http://localhost:8090/health
cd ai-service && npm install && npm run dev

# Database (PostGIS on port 5437, schema applied on first start)
docker compose up -d db
```

Checks:

```bash
cd frontend && npm run lint && npm run build
cd backend && ./mvnw test
cd ai-service && npm run typecheck && npm test
```

## Roadmap

| Phase | Goal | Status |
| --- | --- | --- |
| 1 | Development environment: VS Code, JDK 21, Node.js LTS, PostgreSQL, Git/GitHub; Supabase, OpenAI and Vercel accounts | Done |
| 2 | Project structure: frontend, backend, AI service and database in one repository | Done |
| 3 | Website design: all pages navigable, property form submits, analysis shows sample results | **Done** |
| 4 | Backend API and database: accounts, properties and calculations move to Spring Boot + PostgreSQL | Next |
| 5 | AI Home Inspector: photo upload to object storage, vision model defect and equipment detection | Planned |
| 6 | Location Intelligence, Market Comparator with real data, Document Checker, AI-written reports | Planned |
| 7 | Subscriptions and payments, deployment | Planned |

## Disclaimer

Results are estimates for information only. They are not a formal valuation, survey or financial advice.
