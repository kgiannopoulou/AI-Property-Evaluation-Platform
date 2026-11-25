# Backup and recovery runbook

## Targets

| | Target | How |
| --- | --- | --- |
| Recovery point (RPO) | 24 hours with daily dumps; minutes with the provider's point-in-time recovery | `scripts/db/backup.sh` daily + managed PITR where available |
| Recovery time (RTO) | 1 hour for the database | Restore into a new database, verify, switch `DATABASE_URL` |
| Backup retention | 14 daily backups | `BACKUP_KEEP=14`. This also bounds how long erased personal data survives in backups (stated in the privacy notice) |

Uploaded photos are not in the database dump. They live in the storage bucket, with the bucket's own
versioning. They are deleted after 180 days anyway, and losing them only means re-running an inspection.

## Daily backup

```bash
export PGHOST=db.internal PGUSER=backup PGPASSWORD=... PGDATABASE=property_intelligence
BACKUP_DIR=/var/backups/property BACKUP_KEEP=14 BACKUP_GPG_RECIPIENT=ops@example.org scripts/db/backup.sh
```

The script:

1. Writes row counts of every table (`.counts`) for the restore check.
2. Runs `pg_dump --format=custom`, confirms `pg_restore --list` can read it, then renames it.
   A failed dump never replaces a good one.
3. Encrypts with GPG when `BACKUP_GPG_RECIPIENT` is set. **Required in production**, because the
   dump contains personal data.
4. Writes a SHA-256 checksum and deletes all but the newest `BACKUP_KEEP` backups.

Copy the backup directory to storage in a different account or region. Only operators should
have access to it.

## Restore

```bash
scripts/db/restore.sh /var/backups/property/property_intelligence-20261125T033000Z.dump.gpg property_restore
SCHEMA_TEST=database/tests/schema_test.sql scripts/db/restore.sh <file> property_restore   # also run the schema tests
```

The script checks the checksum, decrypts if needed, and refuses to overwrite an existing
database. It restores into the new one and compares the row count of every table with the
backup's `.counts`. Then:

1. Point a backend instance at `property_restore` and check `GET /api/v1/status`, a sign-in and a valuation.
2. In a maintenance window, switch `DATABASE_URL` (or rename the databases) and restart the backends.
3. **Re-apply erasures**: the audit log records `privacy.erasure` events. Delete again any
   account erased after the backup was taken, so a restore does not bring back deleted personal data:

   ```sql
   -- In the restored database. Take the erasure events from the old (live) database's audit log;
   -- their target is 'user-sha256:' + sha256 of the user id.
   DELETE FROM users u
   WHERE 'user-sha256:' || encode(sha256(convert_to(u.id::text, 'UTF8')), 'hex') IN (
       '<target 1>', '<target 2>'   -- SELECT target FROM audit_events WHERE action = 'privacy.erasure' AND occurred_at > '<backup time>'
   );
   ```

   Also delete those users' uploaded files from storage, if any survived.

## Tests and drills

- **Every push**: CI job `backup-restore` backs up a seeded database, restores it into a new one,
  compares all row counts, runs the schema tests on the copy, and checks that overwriting is refused.
- **Monthly**: restore the latest production backup in staging, time it against the RTO, and record the result.
