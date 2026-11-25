#!/usr/bin/env bash
# Row counts of every table in the public schema, one "table|count" per line, sorted. Used to check
# that a restore brought back everything the backup had.
set -euo pipefail
psql -v ON_ERROR_STOP=1 -At <<'SQL'
SELECT format('SELECT %L || ''|'' || count(*) FROM %I.%I', c.relname, n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind = 'r' AND n.nspname = 'public' AND c.relname <> 'spatial_ref_sys'
ORDER BY c.relname
\gexec
SQL
