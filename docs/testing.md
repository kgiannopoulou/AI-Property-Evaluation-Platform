# Testing before launch

Every area on the Phase 10 list has automated tests that run in CI on each push
([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)). Where a test cannot cover something,
the gap and the manual check that closes it are listed under [Not automated](#not-automated).

| Area | What is checked | Where |
| --- | --- | --- |
| **Registration and login** | BCrypt hashes, hashed session tokens, logout, expiry, lockout after 5 failures, timing-safe unknown emails; terms and privacy notice required at sign-up; per-address limit on sign-ups (10/hour) and sign-ins (30/10 min); sign-ins audited without emails or addresses; terms checkbox in the website | `backend/.../users/AccountServiceTest`, `subscriptions/EnforcementTest#accountEndpoints`, `privacy/PrivacyControllerTest`, `frontend/e2e/layout.spec.ts` (registration) |
| **Property submission and photo uploads** | Seven-step form validation and record upgrades; uploads: 1–24 photos, 8 per area, 5 MB, type sniffed from the bytes (not the name), all validated before any is stored, keys scoped to the owning account | `frontend/properties/submission.test.ts`, `backend/.../inspection/InspectionControllerTest`, `privacy/PrivacyControllerTest` |
| **AI analysis accuracy** | Scorer for a labelled photo set: precision, recall, false alarms, severity and condition agreement, with a release gate (`npm run eval`) | `ai-service/test/evaluation.test.ts`, [`ai-service/eval/`](../ai-service/eval/README.md) |
| **AI failure handling** | Refusals and truncated answers are errors, not empty reports; provider errors mapped; photos that cannot be read are reported; no usable photo = 422; model failures never replaced by invented findings; backend maps ai-service errors to 422/502/503 and gives the analysis back | `ai-service/test/server.test.ts`, `claude.test.ts`, `units.test.ts`, `backend/.../inspection/AiInspectionClientTest`, `InspectionControllerTest`, `subscriptions/EnforcementTest` |
| **Valuation calculations** | Adjustments, weighted median, range width, exclusion of outliers, data-quality gate (`insufficient-data`), rent mode, negotiation offers, user-supplied sales; every answer labelled *automated estimate, not a professional appraisal* | `backend/.../valuation/ValuationEngineTest`, `ValuationControllerTest`, `frontend/valuation/request.test.ts` |
| **Financial calculations** | Transfer tax and VAT, first-home exemption, fees; mortgage payment against the annuity formula, +2-point stress test, zero-rate loan; investment yields, IRR, DSCR, break-even | `backend/.../financial/FinancialCalculatorTest`, `investment/InvestmentCalculatorTest`, `renovation/RenovationPlannerTest` |
| **Payment and subscription status** | Plan catalog in step across backend, database and website; feature and quota enforcement on the server (403/429), atomic usage under concurrency; Stripe webhook signature, tolerance and idempotency; status transitions (active, past_due, canceled); checkout and portal requests | `backend/.../subscriptions/*Test`, `frontend/lib/plans.test.ts` |
| **Report generation** | PDF with every section, Greek text, client branding, insufficient-data wording, the automated-estimate notice; JSON report | `backend/.../reports/ReportServiceTest`, `ModuleEndpointsTest`, `frontend/reports/request.test.ts` |
| **Mobile and desktop layouts** | 12 pages on a phone (Pixel 7) and a desktop (1440×900): heading visible, no sideways scrolling, no console errors, correct navigation per screen size; screenshots attached to the report | `frontend/e2e/layout.spec.ts` (Playwright) |
| **Database backups and recovery** | Back up a seeded database, restore into a new database, identical row counts in every table, schema tests pass on the copy, restore over an existing database refused | `scripts/db/`, CI job `backup-restore`, [runbook](operations/backup-and-recovery.md) |
| **User permissions and data privacy** | Files served only to the owning account (team members share it), other accounts get 404, traversal rejected, access audited; AI processing refused without consent and the analysis not charged; consent history append-only; export contains only the user's data and no secrets; erasure needs the password, removes account, files and records, keeps audit events without the actor; a paid plan must be cancelled first; retention purge; security headers | `backend/.../privacy/PrivacyControllerTest`, `RetentionServiceTest`, `PostgresPrivacyTest` (real PostgreSQL), `database/tests/schema_test.sql` |

## Running the tests

```bash
cd frontend && npm run lint && npm test && npm run build && npm run test:e2e   # e2e: npx playwright install chromium once
cd backend && ./mvnw test        # PostgresPrivacyTest also runs when TEST_DATABASE_URL points at a migrated database
cd ai-service && npm run typecheck && npm test
docker compose exec -T db psql -U property -d property_intelligence -v ON_ERROR_STOP=1 < database/tests/schema_test.sql
```

## Not automated

| Gap | Why | How it is closed before launch |
| --- | --- | --- |
| Accuracy of the real vision model | Needs a labelled set of real property photos (with the owners' permission) and API credits; none is in the repository | Build a set of at least 30 properties labelled from site visits, run `npm run eval`, record the numbers in the release notes |
| Live Stripe payments | No Stripe account keys in development | Stripe test mode end to end: checkout, renewal, failed card (`4000 0000 0000 0341`), cancellation, portal; then one live payment and refund |
| Real devices and browsers | CI uses Chromium emulation | Manual pass on iOS Safari and Android Chrome, plus Firefox desktop |
| Penetration test | Outside the scope of automated tests | External test or an OWASP ASVS level 1 self-assessment before launch |
| Restore of production-size data | CI restores the demo seed | Monthly restore drill in staging (see the runbook) |
