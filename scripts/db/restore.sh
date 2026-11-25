#!/usr/bin/env bash
# Restores a backup into a NEW database and checks it: checksum, restore without errors, the same
# row count in every table as at backup time, and (optionally) the schema tests.
#
#   PGHOST=... PGUSER=... PGPASSWORD=... scripts/db/restore.sh backups/property_intelligence-<stamp>.dump property_restore
#
# It refuses to restore over an existing database: restore next to production, check, then switch
# the application's DATABASE_URL (or rename databases) during a maintenance window.
set -euo pipefail

file=${1:?usage: restore.sh <backup.dump[.gpg]> <new-database-name>}
target=${2:?usage: restore.sh <backup.dump[.gpg]> <new-database-name>}
dir=$(dirname "$file")

if [[ -f "$file.sha256" ]]; then
  (cd "$dir" && sha256sum --check --quiet "$(basename "$file").sha256")
  echo "checksum ok"
else
  echo "warning: no checksum file next to $file" >&2
fi

dump="$file"
if [[ "$file" == *.gpg ]]; then
  dump=$(mktemp)
  trap 'rm -f "$dump"' EXIT
  gpg --batch --yes --decrypt --output "$dump" "$file"
fi

if psql -At -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$target'" | grep -q 1; then
  echo "database $target already exists; choose a new name" >&2
  exit 1
fi
createdb "$target"
pg_restore --exit-on-error --no-owner --no-privileges --dbname="$target" "$dump"
echo "restored into $target"

counts="${file%.dump*}.counts"
if [[ -f "$counts" ]]; then
  if diff <(sort "$counts") <(PGDATABASE="$target" "$(dirname "$0")/row-counts.sh" | sort); then
    echo "row counts match the backup ($(wc -l < "$counts") tables)"
  else
    echo "row counts differ from the backup" >&2
    exit 1
  fi
fi

if [[ -n "${SCHEMA_TEST:-}" ]]; then
  PGDATABASE="$target" psql -v ON_ERROR_STOP=1 -f "$SCHEMA_TEST"
fi
