# AI Property Evaluation Platform

An independent second opinion on a property **before you make an offer**: valuation range, asking-price verdict,
visible defects, ownership and renovation costs, investment returns and a negotiation range you can defend, all in one report.

> **Status: Phase 9 of the roadmap (accounts, subscriptions and payments).** Freemium plans (Free, Premium €9.99,
> Professional €29.99, Business custom: pricing hypotheses to test) with real accounts, Stripe payments and usage
> limits **enforced by the backend** on every API call (see [Accounts, plans and payments](#accounts-plans-and-payments)).
> Phase 8: Location Intelligence, Financial Calculator, Renovation
> Planner, Market Comparator, Investment Calculator, Negotiation Assistant, Building Analysis, Document Checker, Floor
> Plan Reader, Property Comparison and PDF Reports run as separate Java features in the backend, with AI used only for
> reading photographs and floor plans (see [Intelligence modules](#intelligence-modules)).
> Phase 7: the Java backend values a property from comparable
> market records (similar properties nearby, price per m², size, layout, age, condition, floor, elevator, parking,
> energy class, renovation needs, location quality and the local price trend), not from an AI guess, and refuses
> to show a figure when the data is too thin (see [Valuation engine](#valuation-engine)).
> The AI Home Inspector sends property photos through the
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
| [`backend/`](backend) | Java 21 · Spring Boot 4 · OpenPDF | Accounts, plans and usage limits, Stripe billing; valuation engine and the intelligence modules (location, costs, renovation, market, investment, negotiation, building, documents, floor plans, comparison, PDF reports) |
| [`ai-service/`](ai-service) | Node.js · TypeScript · Anthropic SDK | Claude vision model: photo inspection (defects, missing appliances and furniture, condition) and floor plan reading (rooms and printed measurements); structured, validated output |
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
│   └── valuation · location · financial · renovation · market · investment · negotiation · building · documents
│       · floorplan · comparison · reports · inspection · users · subscriptions · common  (properties: later)
├── ai-service/src/
│   └── image-analysis · defect-detection · equipment-detection · vision · report-generation · floor-plan · storage
├── database/
│   ├── migrations/          V1 initial schema · V2 evaluation tables · V3 PostGIS search · V4 submission · V5 AI inspections · V6 valuation engine · V7 modules · V8 accounts and billing
│   ├── tests/               schema and geo-search assertions (rolled back)
│   └── seeds/               demo data for local development
├── compose.yaml             local PostGIS database
└── .github/workflows/ci.yml lint, build and tests for every part + migration check
```

## What the website contains now

| Page | Route | What works |
| --- | --- | --- |
| Home | `/` | Product overview, how it works, module list |
| Registration and login | `/register`, `/login` | Real accounts when the backend is connected (BCrypt passwords, bearer session); otherwise a browser-only demo session |
| Dashboard | `/dashboard` | Recent analyses, portfolio stats, plan usage, module status |
| Add a property | `/properties/new` | Seven-step submission: purchase or rental, address and map location (paste coordinates or use the device location), asking price or monthly rent, size and layout, floor, building age, construction type and condition, energy certificate and rating, heating and cooling, parking/elevator/storage/balcony, appliance checklist (refrigerator, washing machine, dishwasher, oven and stove, air conditioning, heating, furniture), other furniture and appliances, common charges and planned building works, included facilities, photos per area (kitchen, bathroom, bedrooms, living room, exterior, building entrance, other). Validation rules are unit-tested |
| Analysis results | `/properties/[id]` | Valuation range and verdict (sale price, or market rent for rentals), scores, value factors, market comparator, inspection findings (including damaged or missing appliances, heating, cooling, certificate and building works), submitted checklist and facilities, photos grouped by area, purchase and investment calculator or tenant move-in costs and rent-vs-buy, renovation scenarios, negotiation assistant, printable report. **AI Home Inspector**: runs the vision model on the photos (when the backend is connected) and lists each finding with photo thumbnails, severity, confidence, observed vs potential concern, recommendation and a cost range only where cost data exists |
| Saved properties | `/properties` | Search, sort, delete, select up to four to compare |
| Property comparison | `/compare` | Side-by-side table with the best value in each row highlighted |
| Pricing | `/pricing` | Free, Premium (€9.99), Professional (€29.99) and Business (custom) plans; Stripe Checkout (or demo billing) and a Business enquiry form |
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
# With the backend connected, sign up on the website: every backend feature needs an account and follows its plan.
# Without Stripe keys, set BILLING_DEMO_MODE=true in .env to switch plans locally.

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
| 6 | AI image analysis: photos stored by the backend, Claude vision model in the ai-service, structured findings (photo reference, severity, confidence, recommendation, cost where data exists), hidden risks only as potential concerns; schema V5 | Done |
| 7 | Valuation engine: comparable records, published adjustments, weighted median price per m², fair-value range, condition and location scores, concerns, evidence-backed negotiation, data-quality gate that refuses to value on thin evidence; schema V6 | Done |
| 8 | Intelligence modules as separate Java features: Location Intelligence (with environmental risk layers), Financial Calculator, Renovation Planner, Market Comparator, Investment Calculator, Negotiation Assistant, Building Analysis, Document Checker, Floor Plan Reader (AI), Property Comparison, PDF Reports; schema V7 | **Done** |
| 9 | Accounts, subscriptions and payments: freemium plans, backend-enforced features and monthly analyses, Stripe Checkout, Customer Portal and webhooks, teams, API keys, bulk analysis; schema V8 | **Done** |
| — | Website pages for document upload, floor plan upload and the backend modules; saved properties move from browser storage to Spring Boot | Next |
| — | Deployment (Vercel + Java/Node host + managed PostgreSQL), live Stripe products | Planned |

## Accounts, plans and payments

Initial pricing hypotheses to test, not final prices. The plan catalog lives in
[`PlanCatalog.java`](backend/src/main/java/com/propertyapp/subscriptions/PlanCatalog.java); the database `plans`
rows and the website's pricing page mirror it, and tests keep the three in step.

| Plan | Price | Analyses / month | Main features |
| --- | --- | --- | --- |
| Free | €0 | 2 | Valuation range, verdict and scores; basic AI inspection (up to 6 photos); cost calculator; compare 2 |
| Premium | €9.99 / month | 20 | Advanced valuation (comparable evidence, negotiation), PDF reports, full AI inspection (24 photos), location, market, building, documents, floor plans; compare 4 |
| Professional | €29.99 / month | 100 | Everything in Premium, investment tools, client-branded reports; compare 6 |
| Business | Custom | By contract (default 1,000) | Teams sharing one allowance, bulk analysis (up to 50 per call), API access with API keys |

**Enforced in the backend, not by hiding buttons.** An interceptor runs on every `/api/**` call:

1. **Authentication**: a bearer session token (`Authorization: Bearer …`, from `/api/v1/auth/login`) or, on
   Business, an API key (`X-API-Key`). Passwords are BCrypt hashes; tokens and keys are random 256-bit values of
   which only a SHA-256 hash is stored. Repeated failed logins lock the email for 15 minutes. → `401`
2. **Feature**: each endpoint declares the feature it needs (`@RequiresFeature`); plan-specific sizes are checked in
   the controller (photos per inspection, properties per comparison, investment or client sections in a report).
   → `403` with `code: upgrade-required` and the `requiredPlan`
3. **Usage**: endpoints that analyse a property are `@Metered`. One analysis = one property per calendar month (UTC):
   the website sends the property id in `X-Property-Ref`, so re-running a valuation, report or inspection of the same
   property is free. The check and the insert happen in one transaction with the account row locked, so parallel
   requests cannot exceed the limit; a request that fails gives its analysis back. → `429` with `code:
   quota-exceeded`, `limit`, `used` and `resetsOn`; successful calls return `X-Analyses-Limit / -Used / -Remaining`
4. **Free view of the valuation**: the range, verdict, scores and data quality, without the comparable evidence and
   negotiation (Premium).

Team members use the owner's plan and allowance while the owner is on Business; only the owner manages billing.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/v1/auth/register`, `/auth/login`, `/auth/logout`, `GET /auth/me` | Accounts and sessions |
| `GET /api/v1/plans` (public), `GET /api/v1/subscription` | Plans; the caller's plan, status and usage |
| `POST /api/v1/billing/checkout` · `/billing/portal` · `/billing/cancel` | Stripe Checkout, Stripe Customer Portal (change plan, card, cancel); cancel is demo mode only |
| `POST /api/v1/billing/webhook` (public) | Stripe events, verified with the `Stripe-Signature` HMAC and a 5-minute tolerance, processed once |
| `POST /api/v1/billing/business-enquiry` | Business plan enquiry |
| `GET/POST /api/v1/api-keys`, `DELETE /api/v1/api-keys/{id}` | Business API keys (shown once) |
| `GET/POST /api/v1/team`, `POST /team/members`, `DELETE /team/members/{id}` | Business teams and seats |
| `POST /api/v1/bulk/valuations` | Business bulk analysis, each distinct property counted |

**Payments**: Stripe Checkout creates the subscription; `checkout.session.completed`, `customer.subscription.*`,
`invoice.paid` and `invoice.payment_failed` webhooks set the plan, renewal date, cancellation and payment status
(a failed payment keeps the plan as `past_due` while Stripe retries; deletion returns the account to Free). Card
details never reach the backend. Set `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` and the two price ids in `.env`.
Without Stripe, `BILLING_DEMO_MODE=true` switches plans instantly for local development; it is ignored when Stripe is
configured. Without `DATABASE_URL` accounts are kept in memory (development only; a warning is logged).

## Intelligence modules

Each module is its own package under [`backend/.../propertyapp`](backend/src/main/java/com/propertyapp) with a
JSON endpoint and unit tests. AI is used only where reading an image is the job (photographs and floor plans); every
price, cost, score and check is deterministic and traceable. Modules that need data the backend does not have
(market records, facilities, risk layers, ENFIA) say so instead of inventing it.

| Module | Endpoint | What it does | AI |
| --- | --- | --- | --- |
| Location Intelligence | `POST /api/v1/location` | Nearest transport, schools, parks, health care and services with walking times; location score; environmental risks per type as *in a zone*, *outside mapped zones* or *not assessed* (no layer covers the point) | No |
| Financial Calculator | `POST /api/v1/financial` | Transfer tax (on the higher of price and objective value) or VAT, first-home exemption, notary, lawyer, registry and agent fees with VAT; mortgage with a +2-point stress test; monthly ownership costs, with estimates labelled and unknowns (ENFIA) listed | No |
| Renovation Planner | `POST /api/v1/renovation` | Basic, medium and premium scenarios from illustrative cost bands (a verified quote replaces a band); duration; value after works from the valuation engine's condition ladder; which inspection concerns each scenario fixes | No |
| Market Comparator | `POST /api/v1/market` | Price per m² distribution (P10-P90) of completed deals and asking prices, the property's percentile, asking-to-sale gap, quarterly medians; statistics withheld below five records | No |
| Investment Calculator | `POST /api/v1/investment` | Gross and net yield, cap rate, cash-on-cash, cash flow before and after Greek rental tax, break-even occupancy, DSCR, IRR, equity multiple, year-by-year projection | No |
| Negotiation Assistant | `POST /api/v1/negotiation` | Combines valuation, building and documents: offers only when the valuation has enough data, arguments graded strong/moderate/weak by evidence, contract conditions and walk-away signals | No |
| Building Analysis | `POST /api/v1/building` | Structure against the seismic codes (1959, 1985, 1995/2000), pilotis, elevator age and inspection, roof waterproofing, insulation and glazing, legality, planned works and levies; shared costs per m² against a typical range; unknown items stay *unknown* | No |
| Document Checker | `POST /api/v1/documents/check` | Required documents for a sale or lease, validity periods (e.g. engineer's certificate 2 months, energy certificate 10 years), size/owner/address/energy-class consistency across deed, cadastre and plan, encumbrances, unauthorised constructions, permit dates | No |
| Floor Plan Reader | `POST /api/v1/floor-plans` (multipart) | The vision model transcribes rooms and **printed** measurements only (never measured from pixels); the backend checks room total against the declared size and bedroom/bathroom counts | **Yes** |
| Property Comparison | `POST /api/v1/comparison` | 2-6 properties side by side, best value per measure, weighted ranking; missing measures are left out and listed, not guessed | No |
| Reports | `POST /api/v1/reports/pdf` | Runs the requested modules on the given inputs and renders an A4 PDF (Greek text supported); `POST /api/v1/reports` returns the same contents as JSON | No |

On the website the analysis page has a **PDF report** button that sends the property to the backend (shown when
`NEXT_PUBLIC_API_URL` is set). The other modules are available through the API; their pages come next. Load the demo
seed for comparables, facilities, a demo noise zone and building details around Pangrati.

## Valuation engine

`POST /api/v1/valuations` ([`backend/.../valuation`](backend/src/main/java/com/propertyapp/valuation)) answers with the
layout below. The figures are an illustration, not an actual valuation.

| | |
| --- | --- |
| Asking price | €220,000 |
| Estimated fair market value | €195,000–€210,000 |
| Condition score | 72/100 |
| Location score | 85/100 |
| Potential concerns | Older kitchen · windows may need replacement · bathroom requires renovation |
| Suggested negotiation strategy | Start with an offer supported by comparable properties and verified repair estimates, with the evidence behind the suggested maximum offer |

How it works:

1. **Comparables**: same property type and market (sales or lettings), size within ±40 %, observed in the last
   36 months, within 1, 2 or 3 km (the smallest radius with enough evidence). They come from the `comparables`
   table (PostGIS `search_comparables`), plus any sales the user knows of (weighted lower unless verified).
2. **Adjustments** to each comparable's price per m², each one listed in the report: asking-price margin (5 %, 3 %
   for rents), size, effective age (construction or renovation year), condition, floor and elevator, parking,
   energy class, bathrooms, and the local price trend when at least six transactions span nine months. Attributes
   the source did not record are skipped, never guessed, and count against data quality.
3. Records needing more than 35 % gross adjustment, and outliers, are excluded with the reason shown. The rest are
   weighted by evidence type (completed deal or asking price), distance, recency, size similarity and adjustment size.
4. **Estimate** = weighted median adjusted price per m² × size; the range widens when comparables disagree, the
   sample is small or there are only asking prices.
5. **Data quality** is graded high, medium, low or **insufficient**. With fewer than 3 usable comparables, asking
   prices alone below 5 records, a spread above ±30 % or no market database, the engine returns
   `insufficient-data`: no range, no verdict, no offer, but the reasons and what would help.
6. **Condition score** (declared condition, age, energy class, AI and rule-based findings) and **location score**
   (rail, groceries, pharmacy, health, schools, parks and amenities within 1 km, or the district reference) are
   reported with their breakdown.
7. **Negotiation**: opening offer, target and maximum offer with the evidence behind them. Repair costs lower the
   offers only when verified (a written quote or survey) and only beyond what the condition adjustment already covers.

Set `DATABASE_URL` (see `.env.example`) to connect the backend to the database; load the demo seed for comparables
around Pangrati. Without it the endpoint answers `insufficient-data`. On the website the panel appears at the top of
the analysis page when `NEXT_PUBLIC_API_URL` is set, with an editor for repair estimates.

## Disclaimer

Results are estimates for information only. They are not a formal valuation, survey or financial advice.
