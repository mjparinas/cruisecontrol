#!/usr/bin/env bash
# Prints the next patch version after the highest x.y.z tag read from stdin, one tag per line.
# Keeps the tag's v prefix and prints v0.1.0 when there are no version tags.
# Matches Versions.nextPatch in core, which the app uses for its own releases.
set -euo pipefail

{ grep -E '^v?[0-9]+\.[0-9]+\.[0-9]+$' || true; } \
  | sed -E 's/^(v?)([0-9]+)\.([0-9]+)\.([0-9]+)$/\2 \3 \4 \1/' \
  | sort -n -k1,1 -k2,2 -k3,3 \
  | tail -n 1 \
  | awk '{ print $4 $1 "." $2 "." ($3 + 1); found = 1 } END { if (!found) print "v0.1.0" }'
