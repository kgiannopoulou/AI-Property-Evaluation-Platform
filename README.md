# AI Property Evaluation Platform

An independent second opinion on a property **before you make an offer**: valuation range, asking-price verdict,
visible defects, ownership and renovation costs, investment returns and a negotiation range you can defend, all in one report.

> **Status: Phase 6 of the roadmap (AI image analysis).** The AI Home Inspector sends property photos through the
> Java backend to the TypeScript ai-service, where a Claude vision model returns structured findings (see
> [`ai-service/`](ai-service)). Users can submit a purchase or a rental with location,
> building, energy, heating and cooling details, an appliance and furniture checklist, building expenses, facilities
> and photos per area. The PostgreSQL + PostGIS schema is in place and tested in CI (see [`database/`](database)).
> The website still runs in the browser using a rule-based
> **sample engine** with illustrative reference prices. It is not market data, does no image analysis and is not a
> valuation. Every sample result is labelled as such in the interface.

## Architecture

| Part | Technology | Responsibility |
| --- | --- | --- |
| [`frontend/`](frontend) | TypeScript · React 19 · Next.js 16 · Tailwind CSS 4 | Website, dashboard, property forms, photo uploads, charts and reports |
| [`backend/`](backend) | Java 21 · Spring Boot 4 | Accounts, properties, permissions, subscriptions, calculations, comparisons and business rules |
| [`ai-service/`](ai-service) | Node.js · TypeScript · Anthropic SDK | Photo analysis with a Claude vision model: defects, missing appliances and furniture, condition; structured findings with safety rules and cost lookup |
| [`database/`](database) | PostgreSQL · PostGIS | 17 core tables (users, properties, images, features, equipment, buildings, inspections, valuations, comparables, location data, expenses, renovations, investments, documents, saved properties, subscriptions, reports) and PostGIS search for nearby facilities and comparables |

```
Browser ──► frontend (Next.js) ──► backend (Spring Boot) ──► PostgreSQL + PostGIS
                                         │
                                         │      ▲ photos stored, keys sent
                                         ▼      │ structured findings
                                       ai-service (Node) ──► Claude vision model
```

The three applications talk over HTTP APIs. **The browser never receives secret AI, storage or payment keys**:
only the backend and AI service read them, from environment variables (see [`.env.example`](.env.example)).

Photos are stored in a local directory in development or a private Supabase Storage bucket; the ai-service reads
them by storage key. Supporting services planned for later phases: Google Maps or Mapbox for location
intelligence, Stripe or a local provider for subscriptions, and Vercel plus a Java/Node host for deployment.

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
│   └── image-analysis · defect-detection · equipment-detection · vision · report-generation · storage
├── database/
│   ├── migrations/          V1 initial schema · V2 evaluation tables · V3 PostGIS search · V4 submission · V5 AI inspections
│   ├── tests/               schema and geo-search assertions (rolled back)
│   └── seeds/               demo data for local development
├── compose.yaml             local PostGIS database
└── .github/workflows/ci.yml lint, build and tests for every part + migration check
```

## What the website contains now

| Page | Route | What works |
| --- | --- | --- |
| Home | `/` | Product overview, how it works, module list |
| Registration and login | `/register`, `/login` | Form validation; prototype session stored in the browser (no real auth yet) |
| Dashboard | `/dashboard` | Recent analyses, portfolio stats, plan usage, module status |
| Add a property | `/properties/new` | Seven-step submission: purchase or rental, address and map location (paste coordinates or use the device location), asking price or monthly rent, size and layout, floor, building age, construction type and condition, energy certificate and rating, heating and cooling, parking/elevator/storage/balcony, appliance checklist (refrigerator, washing machine, dishwasher, oven and stove, air conditioning, heating, furniture), other furniture and appliances, common charges and planned building works, included facilities, photos per area (kitchen, bathroom, bedrooms, living room, exterior, building entrance, other). Validation rules are unit-tested |
| Analysis results | `/properties/[id]` | Valuation range and verdict (sale price, or market rent for rentals), scores, value factors, market comparator, inspection findings (including damaged or missing appliances, heating, cooling, certificate and building works), submitted checklist and facilities, photos grouped by area, purchase and investment calculator or tenant move-in costs and rent-vs-buy, renovation scenarios, negotiation assistant, printable report. **AI Home Inspector**: runs the vision model on the photos (when the backend is connected) and lists each finding with photo thumbnails, severity, confidence, observed vs potential concern, recommendation and a cost range only where cost data exists |
| Saved properties | `/properties` | Search, sort, delete, select up to four to compare |
| Property comparison | `/compare` | Side-by-side table with the best value in each row highlighted |
| Pricing | `/pricing` | Free, Premium, Professional and Business plans, monthly/annual toggle |
| Profile and settings | `/settings` | Profile, mortgage assumptions used by the calculator, notifications, reset demo data |

Data lives in `localStorage` for now. The store hooks have the same shape the API client will have, so moving to
the backend swaps the transport without changing the pages. Records saved by earlier versions are upgraded on load
with neutral "unknown" values (see `frontend/properties/submission.ts`).

## Running locally

Requirements: Node.js 22.18+ (24 LTS recommended), Java 21, and optionally Docker for the database.

```bash
# Website: http://localhost:3000
cd frontend && npm install && npm run dev

# Backend: http://localhost:8080/api/v1/status
cd backend && ./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run

# AI service: http://localhost:8090/health  (needs ANTHROPIC_API_KEY in .env)
cd ai-service && npm install && npm run dev

# To use the AI Home Inspector from the website: copy .env.example to .env and fill in ANTHROPIC_API_KEY and
# AI_SERVICE_TOKEN, then put NEXT_PUBLIC_API_URL=http://localhost:8080 in frontend/.env.local

# Database (PostGIS on port 5437, schema applied on first start)
docker compose up -d db
docker compose exec -T db psql -U property -d property_intelligence < database/seeds/demo_seed.sql   # optional demo data
```

Checks:

```bash
cd frontend && npm run lint && npm test && npm run build
cd backend && ./mvnw test
cd ai-service && npm run typecheck && npm test
docker compose exec -T db psql -U property -d property_intelligence -v ON_ERROR_STOP=1 < database/tests/schema_test.sql
```

## Roadmap

| Phase | Goal | Status |
| --- | --- | --- |
| 1 | Development environment: VS Code, JDK 21, Node.js LTS, PostgreSQL, Git/GitHub; Supabase, OpenAI and Vercel accounts | Done |
| 2 | Project structure: frontend, backend, AI service and database in one repository | Done |
| 3 | Website design: all pages navigable, property form submits, analysis shows sample results | Done |
| 4 | Database: 17 core tables, PostGIS geographical search (nearby pharmacies, transport, schools; comparables), constraints, demo seed, tests in CI | Done |
| 5 | Property submission: purchase or rental, full property details, appliance checklist, building expenses, facilities, photos per area; schema V4 | Done |
| 6 | AI image analysis: photos stored by the backend, Claude vision model in the ai-service, structured findings (photo reference, severity, confidence, recommendation, cost where data exists), hidden risks only as potential concerns; schema V5 | **Done** |
| — | Backend API: accounts, properties and calculations move from browser storage to Spring Boot on this schema | Next |
| — | Location Intelligence, Market Comparator with real data, Document Checker, AI-written reports | Planned |
| — | Subscriptions and payments, deployment | Planned |

## Disclaimer

Results are estimates for information only. They are not a formal valuation, survey or financial advice.
