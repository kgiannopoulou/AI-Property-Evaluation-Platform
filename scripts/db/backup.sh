#!/usr/bin/env bash
# Database backup: a compressed custom-format dump, verified, checksummed, with row counts for the
# restore check, optionally encrypted, and older backups pruned.
#
#   PGHOST=... PGPORT=... PGUSER=... PGPASSWORD=... PGDATABASE=property_intelligence \
#   BACKUP_DIR=/var/backups/property BACKUP_KEEP=14 [BACKUP_GPG_RECIPIENT=ops@example.org] scripts/db/backup.sh
#
# Backups contain personal data: keep them encrypted, in a separate account or region, with access
# limited to operators. BACKUP_KEEP (days of daily backups) also bounds how long data erased from
# the live database survives in backups (see docs/operations/backup-and-recovery.md).
set -euo pipefail

: "${PGDATABASE:?set PGDATABASE (and PGHOST, PGUSER, PGPASSWORD)}"
dir=${BACKUP_DIR:-backups}
keep=${BACKUP_KEEP:-14}
mkdir -p "$dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
base="$dir/${PGDATABASE}-${stamp}"

# Row counts first, from the same snapshot the dump will see as closely as possible.
"$(dirname "$0")/row-counts.sh" > "$base.counts"

pg_dump --format=custom --compress=9 --no-owner --no-privileges --file="$base.dump.partial"
# A dump that pg_restore cannot list is not a backup.
pg_restore --list "$base.dump.partial" > /dev/null
mv "$base.dump.partial" "$base.dump"

if [[ -n "${BACKUP_GPG_RECIPIENT:-}" ]]; then
  gpg --batch --yes --encrypt --recipient "$BACKUP_GPG_RECIPIENT" --output "$base.dump.gpg" "$base.dump"
  rm -f "$base.dump"
  file="$base.dump.gpg"
else
  file="$base.dump"
fi
(cd "$dir" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256")

# Keep the newest $keep backups.
ls -1t "$dir"/"${PGDATABASE}"-*.dump* 2>/dev/null | grep -v '\.sha256$' | tail -n +$((keep + 1)) | while read -r old; do
  stem=${old%.dump*}
  rm -f "$old" "$old.sha256" "$stem.counts"
done

echo "$file"
