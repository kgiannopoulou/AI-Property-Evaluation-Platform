# Security and privacy

How the platform protects accounts and personal data, and how it meets the GDPR. The user-facing
version is the website's privacy notice (`/privacy`) and terms (`/terms`).

## GDPR

| Requirement | Implementation |
| --- | --- |
| Lawful basis and consent (art. 6, 7) | Terms and privacy notice accepted at sign-up and stored with the policy version (`consent_records`, append-only). Sending photos and floor plans to the AI provider needs `ai-photo-processing` consent. The **backend** refuses uploads without it (`403 consent-required`) and does not count the analysis. Marketing email is opt-in. Optional consents can be withdrawn in Settings → Privacy; withdrawal takes effect on the next request |
| Transparency (art. 13) | `/privacy`: data, purposes, legal bases, processors, transfers, retention, rights. `/terms`: estimate vs appraisal, document checker limits, AI limits. Retention figures are tested against the backend configuration |
| Access and portability (art. 15, 20) | `GET /api/v1/privacy/export`: JSON with account, plan, API key metadata, team, usage, consent history, uploaded files and activity. Secrets (password hash, token and key hashes, IP hashes) are never included. Other team members appear only in the owner's export |
| Erasure (art. 17) | `POST /api/v1/privacy/erasure` with the password: deletes uploaded files, then the user row. Sessions, keys, subscriptions, team, consents, file records, usage and enquiries go with it (cascades tested). Audit events stay without the actor. An active Stripe plan must be cancelled first, so the person is not charged after deletion |
| Storage limitation (art. 5(1)(e)) | Nightly purge (`RetentionService` + `purge_expired_data()`): uploads after 180 days, audit events after 365 days, usage after 24 months, payment webhook ids after 90 days, enquiries after 24 months, revoked keys after 90 days, expired sessions. All periods are configurable (`RETENTION_*`). Backups roll over in 14 days |
| Data minimisation | The AI provider receives images and property type only, no name or email. The audit log stores salted hashes of IP addresses and emails. Card details stay with Stripe |
| Accountability | Audit log of sign-ins, failures, lockouts, consent changes, exports, erasures, file access, API keys, team changes, billing actions, webhooks and denied access. Users see their own activity in Settings |
| Automated decisions (art. 22) | Valuations make no decision about the user. They are labelled as automated estimates in the API (`notice.kind = automated-estimate`), the website and the PDF |

## Secure file access

- Keys start with the owning account: `accounts/<account>/properties/<property>/<random>.<ext>`.
  Each upload is recorded in `stored_files` with its owner, uploader, type, size and deletion date.
- `GET /api/v1/files?key=…` serves a file only to its account (team members use the owner's
  account). Unknown keys and other accounts' keys both return `404`, so keys cannot be probed.
  Responses are `Cache-Control: no-store, private` with `nosniff`.
- File types come from the bytes (JPEG, PNG, WebP); names and browser content types are ignored.
  Paths are normalised and kept under the storage root.
- Users can delete an upload (`DELETE /api/v1/files?key=…`). Every read, deletion and refusal is audited.

## Application security controls

| Threat | Control |
| --- | --- |
| Credential stuffing, brute force | Per-email lockout (5 failures / 15 min), per-address limits on sign-in and sign-up, equal-time response for unknown emails, BCrypt cost 12 |
| Stolen tokens or keys from the database | Only SHA-256 hashes of session tokens and API keys are stored. API keys are shown once and can be revoked |
| Bypassing plan limits | Features, sizes and monthly analyses are enforced by an interceptor on every `/api/**` call, under a row lock. The website only mirrors the rules |
| Access to other users' data | Ownership checks on files. Team access goes through the owner's account. Teams and API keys are managed only by their owner |
| Clickjacking, content sniffing, data in shared caches | `X-Frame-Options: DENY`, `frame-ancestors 'none'`, `nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on the API, HSTS on HTTPS |
| Forged payment events | Stripe webhooks verified with an HMAC signature and a 5-minute tolerance, and processed once |
| Prompt injection through images | The model returns schema-validated JSON only. Findings must reference submitted photos. Hidden-risk categories are always downgraded to potential concerns |
| Leaking internals | No stack traces or exception messages in error responses. Secrets come from the environment and never reach the browser |
| Tampering with the audit trail | Database trigger: no updates, and deletes only inside the retention purge |

## Configuration for production

| Variable | Purpose |
| --- | --- |
| `AUDIT_IP_SALT` | Long random value. Without it a random salt is used and hashes change on restart |
| `FORWARD_HEADERS=framework` | Behind a TLS proxy, so HTTPS (HSTS) and client addresses are known |
| `RETENTION_*` | Override retention periods (keep the privacy notice in step) |
| `FRONTEND_ORIGIN` | The only origin allowed by CORS |
| `BILLING_DEMO_MODE=false` | Never enable demo billing in production |
