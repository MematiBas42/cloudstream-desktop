#!/usr/bin/env bash
# ==============================================================================
# tools/check-anti-stubs.sh
# Automated scanner to enforce Zero-Shim / Zero-Fallback anti-stub policy
# Fails with exit code 1 if fake stubs, empty catch blocks or TODOs are found.
# ==============================================================================

set -euo pipefail

TARGET_PATH="${1:-}"

if [[ -z "$TARGET_PATH" ]]; then
    echo "Usage: $0 <file_or_directory_path>"
    exit 1
fi

if [[ ! -e "$TARGET_PATH" ]]; then
    echo "[ERROR] Target does not exist: $TARGET_PATH" >&2
    exit 2
fi

STUB_COUNT=0

check_file() {
    local file="$1"
    local issues=0

    # Check for TODO / FIXME
    local todos
    todos=$(grep -n -E '//[[:space:]]*(TODO|FIXME)' "$file" || true)
    if [[ -n "$todos" ]]; then
        echo "[STUB DETECTED] TODO/FIXME comments in $file:"
        echo "$todos"
        issues=$((issues + $(echo "$todos" | wc -l)))
    fi

    # Check for empty catch blocks: } catch (...) { }
    local empty_catches
    empty_catches=$(python3 -c "
import sys, re
with open('$file', 'r', errors='ignore') as f:
    text = f.read()
matches = list(re.finditer(r'catch\s*\([^)]*\)\s*\{\s*\}', text))
for m in matches:
    start_line = text[:m.start()].count('\n') + 1
    print(f'Line {start_line}: {m.group(0)}')
" || true)
    if [[ -n "$empty_catches" ]]; then
        echo "[STUB DETECTED] Empty catch blocks in $file:"
        echo "$empty_catches"
        issues=$((issues + $(echo "$empty_catches" | wc -l)))
    fi

    # Check for NotImplementedError / UnsupportedOperationException
    local nie
    nie=$(grep -n -E 'throw[[:space:]]+(NotImplementedError|UnsupportedOperationException)' "$file" || true)
    if [[ -n "$nie" ]]; then
        # Check if this file is an abstract interface contract where NIE is standard
        if ! grep -q -E 'interface|abstract class' "$file"; then
            echo "[STUB DETECTED] NotImplementedError in non-interface file $file:"
            echo "$nie"
            issues=$((issues + $(echo "$nie" | wc -l)))
        fi
    fi

    # Check for fake return null / fake return false in single line declarations
    local fake_returns
    fake_returns=$(grep -n -E 'fun[[:space:]]+[A-Za-z0-9_]+\([^)]*\)[[:space:]]*:[[:space:]]*[A-Za-z0-9_<>?.]+[[:space:]]*=[[:space:]]*(null|false|true|emptyList\(\)|emptyMap\(\))' "$file" || true)
    if [[ -n "$fake_returns" ]]; then
        # Filter out legitimate defaults like hasPreview, isTv, etc. if in test mocks
        if [[ "$file" != *"test"* ]] && [[ "$file" != *"Test"* ]]; then
            echo "[SUSPICIOUS STUB] Hardcoded single-expression fake return in $file:"
            echo "$fake_returns"
            # Note: warning unless confirmed fake
        fi
    fi

    STUB_COUNT=$((STUB_COUNT + issues))
}

echo "=================================================="
echo "ANTI-STUB SCANNER: $TARGET_PATH"
echo "=================================================="

if [[ -f "$TARGET_PATH" ]]; then
    check_file "$TARGET_PATH"
elif [[ -d "$TARGET_PATH" ]]; then
    while IFS= read -r -d '' kt_file; do
        check_file "$kt_file"
    done < <(find "$TARGET_PATH" -name "*.kt" -not -path "*/build/*" -not -path "*/upstream/*" -print0)
fi

echo "=================================================="
if [[ "$STUB_COUNT" -gt 0 ]]; then
    echo "RESULT: ANTI-STUB SCAN FAILED ($STUB_COUNT defect(s) detected)"
    exit 1
else
    echo "RESULT: ZERO STUBS DETECTED (Clean)"
    exit 0
fi
