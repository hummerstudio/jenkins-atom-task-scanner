#!/usr/bin/env bash
# Markers are counted per line, the budget is absolute, and a word inside another word is not a marker.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"; mkdir -p "$dir/src"
cat >"$dir/src/A.java" <<'JAVA'
public class A {
    // TODO: rename this
    // FIXME later
    // TODOSTR is not a marker
    String s = "XXX";
}
JAVA
printf '{"maxMarkers": "-1"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" markers)" = "3" ] || fail "markers: $(cat "$dir/output.json")"
[ "$(output "$dir" todo)" = "1" ] || fail "todo: $(cat "$dir/output.json")"
[ "$(output "$dir" fixme)" = "1" ] || fail "fixme: $(cat "$dir/output.json")"
grep -q "rename this" "$dir/out/tasks/index.html" || fail "the marker text is missing"

# a budget of one trips it
dir="$(mktemp -d)"
printf '// TODO one\n// TODO two\n' >"$dir/x.java"
printf '{"maxMarkers": "1"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "two markers over a budget of one must fail"
grep -q "the budget is 1" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

# markers can be chosen
dir="$(mktemp -d)"
printf '# DEPRECATED: old api\n# TODO fine\n' >"$dir/x.py"
printf '{"markers": "DEPRECATED"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "custom marker run failed"
[ "$(output "$dir" markers)" = "1" ] || fail "custom marker count: $(cat "$dir/output.json")"

echo "smoke ok: marker counting, word boundaries, budget, custom markers"
