#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_CP="${ROOT_DIR}/out"
JAVA_BIN="${JAVA_BIN:-java}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_run() {
  local description="$1"
  shift
  if "$@"; then
    echo "ok - ${description}"
  else
    fail "${description}"
  fi
}

assert_exit() {
  local expected="$1"
  shift
  local description="$1"
  shift
  local output
  set +e
  output=$("$@" >/tmp/cli-smoke.stdout 2>/tmp/cli-smoke.stderr)
  local status=$?
  set -e
  if [[ ${status} -ne ${expected} ]]; then
    echo "stdout:"
    cat /tmp/cli-smoke.stdout || true
    echo "stderr:"
    cat /tmp/cli-smoke.stderr || true
    fail "${description} (expected exit ${expected}, got ${status})"
  fi
  echo "ok - ${description}"
}

# Ensure the classpath is present
assert_run "classpath exists" test -d "${APP_CP}"

# 1) help should print usage and exit 0
assert_run "help command" \
  "${JAVA_BIN}" -cp "${APP_CP}" application.Main help >/tmp/cli-smoke-help.txt
grep -q "usage: app" /tmp/cli-smoke-help.txt || fail "help output missing usage"
grep -q "record-to-plain" /tmp/cli-smoke-help.txt || fail "help output missing record-to-plain"

# 2) no-arg invocation should also show help (exit 0)
assert_run "help default invocation" \
  "${JAVA_BIN}" -cp "${APP_CP}" application.Main >/tmp/cli-smoke-help-default.txt
grep -q "usage: app" /tmp/cli-smoke-help-default.txt || fail "default invocation missing usage"

# 3) invalid command should exit 2
assert_exit 2 "unknown command exits 2" \
  "${JAVA_BIN}" -cp "${APP_CP}" application.Main nope >/tmp/cli-smoke-unknown.txt

# 4) print valid FEN (flag)
assert_run "print valid FEN" \
  "${JAVA_BIN}" -cp "${APP_CP}" application.Main print --fen "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" >/tmp/cli-smoke-print.txt

# 5) print valid FEN (positional)
assert_run "print valid positional FEN" \
  "${JAVA_BIN}" -cp "${APP_CP}" application.Main print "8/8/8/2k5/2Pp4/1P1K4/8/8 w - - 0 1" >/tmp/cli-smoke-print-pos.txt

# 6) print invalid FEN should exit non-zero (expected 3)
assert_exit 3 "print invalid FEN" \
  "${JAVA_BIN}" -cp "${APP_CP}" application.Main print --fen "not-a-fen"

# 7) clean command should exit 0
assert_run "clean session cache" \
  "${JAVA_BIN}" -cp "${APP_CP}" application.Main clean >/tmp/cli-smoke-clean.txt

# 8) record roundtrip: create tiny .record file and convert to plain/csv
TMP_DIR="$(mktemp -d)"
RECORD_FILE="${TMP_DIR}/sample.record"
PLAIN_FILE="${TMP_DIR}/sample.plain"
CSV_FILE="${TMP_DIR}/sample.csv"

cat >"${RECORD_FILE}" <<'JSON'
[{
  "created": 0,
  "engine": "smoke-test",
  "parent": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
  "position": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
  "description": "start position test",
  "tags": ["test"],
  "analysis": ["info depth 6 multipv 1 score cp 13 pv e2e4 e7e5 g1f3"]
}]
JSON

assert_run "record-to-plain conversion" \
  "${JAVA_BIN}" -cp "${APP_CP}" application.Main record-to-plain --input "${RECORD_FILE}" --output "${PLAIN_FILE}"
assert_run "record-to-csv conversion" \
  "${JAVA_BIN}" -cp "${APP_CP}" application.Main record-to-csv --input "${RECORD_FILE}" --output "${CSV_FILE}"

test -s "${PLAIN_FILE}" || fail "plain output missing"
test -s "${CSV_FILE}" || fail "csv output missing"

rm -rf "${TMP_DIR}"
