# Launch checklist

Status on 2026-11-25. ✅ done in this repository · ⬜ needs an account, a person or a decision outside the code.

## Product and legal

- ✅ Valuations labelled *automated estimate, not a professional appraisal* in the API (`notice`), website, PDF, terms and pricing FAQ
- ✅ Document Checker labelled *assistance tool, not a legal certification* in the API, PDF, terms, pricing FAQ and sign-up
- ✅ Privacy notice and terms pages, versioned (`2026-11-25`), consent stored with the version
- ⬜ Operator's legal name, address and privacy contact filled in on `/privacy` and `/terms`
- ⬜ Lawyer review of the privacy notice and terms (Greek and EU consumer law)
- ⬜ Data processing agreements signed with Anthropic, Stripe, the storage and hosting providers; transfer basis confirmed
- ⬜ Record of processing activities (GDPR art. 30) and a short DPIA for the AI photo analysis

## Security

- ✅ Server-enforced plans, consents and ownership; audit log; security headers; rate limits; hashed tokens and keys
- ✅ Secure file access with ownership checks and audited reads
- ✅ No stack traces or messages in error responses
- ⬜ `AUDIT_IP_SALT`, `FORWARD_HEADERS=framework`, `FRONTEND_ORIGIN`, `BILLING_DEMO_MODE=false` set in production
- ⬜ TLS everywhere (hosting), database not publicly reachable, least-privilege database user
- ⬜ Dependency and container scanning in CI (e.g. Dependabot, `npm audit`, OWASP dependency-check)
- ⬜ Penetration test or OWASP ASVS level 1 self-assessment

## Data

- ✅ Retention purge (daily) and erasure, tested on PostgreSQL
- ✅ Backup and restore scripts, verified restore in CI
- ⬜ Daily encrypted backups scheduled off-site (`BACKUP_GPG_RECIPIENT`), first restore drill in staging
- ⬜ Managed point-in-time recovery enabled on the production database

## Testing

- ✅ The ten pre-launch areas covered in CI (see [testing.md](testing.md))
- ⬜ AI accuracy measured on a labelled photo set (`npm run eval`) and the numbers recorded
- ⬜ Stripe test-mode run: checkout, renewal, failed card, cancellation, portal; then one live payment and refund
- ⬜ Manual pass on iOS Safari, Android Chrome and Firefox

## Operations

- ⬜ Hosting: website (e.g. Vercel), backend and ai-service hosts, managed PostgreSQL with PostGIS
- ⬜ Monitoring and alerts: uptime, error rate, AI service failures, failed webhooks, purge job result
- ⬜ Live Stripe products and prices; webhook endpoint registered
