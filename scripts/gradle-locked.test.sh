#!/usr/bin/env bash

set -euo pipefail

wrapper="$(cd "$(dirname "$0")" && pwd)/gradle-locked"
fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

nested_root="$fixture_root/outer"
mkdir -p "$nested_root/third_party/argentum-engine/scripts" "$nested_root/scripts"
cp "$wrapper" "$nested_root/third_party/argentum-engine/scripts/gradle-locked"
touch "$nested_root/third_party/argentum-engine/gradlew"
chmod +x "$nested_root/third_party/argentum-engine/gradlew"

trace="$fixture_root/trace"
cat >"$nested_root/scripts/build-locked" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" >"$TRACE_FILE"
EOF
chmod +x "$nested_root/scripts/build-locked"
TRACE_FILE="$trace" "$nested_root/third_party/argentum-engine/scripts/gradle-locked" :gym:test
test "$(sed -n '2p' "$trace")" = ":gym:test"
test "$(sed -n '1p' "$trace")" = "$nested_root/third_party/argentum-engine/gradlew"

standalone_root="$fixture_root/standalone"
mkdir -p "$standalone_root/scripts"
cp "$wrapper" "$standalone_root/scripts/gradle-locked"
touch "$standalone_root/gradlew"
chmod +x "$standalone_root/gradlew"
if "$standalone_root/scripts/gradle-locked" --version >"$fixture_root/standalone.out" 2>&1; then
    echo "gradle-locked test: standalone execution without shlock unexpectedly succeeded" >&2
    exit 1
fi
grep -q "refusing an un-serialized build" "$fixture_root/standalone.out"

echo "gradle-locked tests passed"
