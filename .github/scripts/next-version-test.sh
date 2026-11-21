#!/usr/bin/env bash
# Checks next-version.sh against the same cases as UpdatesTest.picksTheNextPatchVersion.
set -euo pipefail

script="$(dirname "$0")/next-version.sh"
failed=0

check() {
  local expected="$1" tags="$2" actual
  actual="$(printf '%s' "$tags" | "$script")"
  if [ "$actual" != "$expected" ]; then
    echo "Expected $expected but got $actual for tags: $(echo $tags)"
    failed=1
  fi
}

check v0.4.5 $'v0.4.4\nv0.10.0-beta\nv0.3.9\nlatest'
check v0.10.1 $'v0.9.9\nv0.10.0'
check v0.7.21 $'v0.7.9\nv0.7.20\nv0.7.19'
check 1.2.4 '1.2.3'
check v0.1.0 ''
check v0.1.0 'nightly'

[ "$failed" -eq 0 ] && echo "next-version.sh: all checks passed"
exit "$failed"
