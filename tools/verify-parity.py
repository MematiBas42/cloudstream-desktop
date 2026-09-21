#!/usr/bin/env python3
"""
tools/verify-parity.py
Automated mechanical parity verification gate between upstream and desktop files.
Verifies line count ratio (>=85% target) and function/class signature coverage.
"""

import sys
import os
import re

def clean_code_lines(content: str):
    """Strip block comments, line comments, and blank lines to count true LOC."""
    # Remove block comments /* ... */
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)
    lines = []
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('//') or stripped.startswith('*'):
            continue
        lines.append(stripped)
    return lines

def extract_signatures(content: str):
    """Extract class, interface, object, fun, and val/var declarations."""
    # Remove strings to avoid false matches
    content = re.sub(r'"(?:\\.|[^"\\])*"', '""', content)
    signatures = {
        'types': set(),
        'functions': set(),
        'properties': set()
    }

    # Types: class, interface, object
    for match in re.finditer(r'\b(?:class|interface|object|enum\s+class)\s+([A-Za-z0-9_]+)', content):
        signatures['types'].add(match.group(1))

    # Functions: fun ... name(
    for match in re.finditer(r'\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_<>?.]+\.)?([A-Za-z0-9_]+)\s*\(', content):
        name = match.group(1)
        if name not in ('if', 'when', 'for', 'while'):
            signatures['functions'].add(name)

    # Properties: val / var
    for match in re.finditer(r'\b(?:val|var)\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_<>?.]+\.)?([A-Za-z0-9_]+)\s*[:=]', content):
        signatures['properties'].add(match.group(1))

    return signatures

def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <upstream_file> <desktop_file> [--min-ratio RATIO] [--allow-missing]")
        sys.exit(1)

    upstream_path = sys.argv[1]
    desktop_path = sys.argv[2]

    min_ratio = 85.0
    allow_missing = False

    idx = 3
    while idx < len(sys.argv):
        if sys.argv[idx] == '--min-ratio' and idx + 1 < len(sys.argv):
            min_ratio = float(sys.argv[idx + 1])
            idx += 2
        elif sys.argv[idx] == '--allow-missing':
            allow_missing = True
            idx += 1
        else:
            idx += 1

    if not os.path.exists(upstream_path):
        print(f"[ERROR] Upstream file not found: {upstream_path}", file=sys.stderr)
        sys.exit(2)

    if not os.path.exists(desktop_path):
        print(f"[ERROR] Desktop file not found: {desktop_path}", file=sys.stderr)
        sys.exit(2)

    with open(upstream_path, 'r', encoding='utf-8', errors='replace') as f:
        up_raw = f.read()
    with open(desktop_path, 'r', encoding='utf-8', errors='replace') as f:
        desk_raw = f.read()

    up_lines = clean_code_lines(up_raw)
    desk_lines = clean_code_lines(desk_raw)

    up_loc = len(up_lines)
    desk_loc = len(desk_lines)

    ratio = (desk_loc / up_loc * 100.0) if up_loc > 0 else 100.0

    up_sigs = extract_signatures(up_raw)
    desk_sigs = extract_signatures(desk_raw)

    missing_types = up_sigs['types'] - desk_sigs['types']
    missing_funs = up_sigs['functions'] - desk_sigs['functions']

    # Check for @PortSource header in desktop file
    has_port_source = '@PortSource' in desk_raw

    print("==================================================")
    print(f"PARITY VERIFICATION: {os.path.basename(desktop_path)}")
    print("==================================================")
    print(f"Upstream: {upstream_path} ({up_loc} LOC)")
    print(f"Desktop:  {desktop_path} ({desk_loc} LOC)")
    print(f"Retention Ratio: {ratio:.1f}% (Required: >={min_ratio}%)")
    print(f"Provenance Header (@PortSource): {'PRESENT' if has_port_source else 'MISSING'}")
    print(f"Functions: {len(desk_sigs['functions'])}/{len(up_sigs['functions'])} (Missing: {len(missing_funs)})")
    print(f"Types:     {len(desk_sigs['types'])}/{len(up_sigs['types'])} (Missing: {len(missing_types)})")

    failed = False

    if ratio < min_ratio and not allow_missing:
        print(f"[FAIL] Retention ratio {ratio:.1f}% is below threshold {min_ratio}%")
        failed = True

    if missing_types:
        print(f"[WARN] Missing types: {', '.join(sorted(missing_types))}")
        if not allow_missing:
            failed = True

    if missing_funs:
        print(f"[WARN] Missing functions: {', '.join(sorted(missing_funs))}")
        if not allow_missing:
            failed = True

    if not has_port_source:
        print("[WARN] Missing @PortSource provenance header in desktop file")

    print("==================================================")
    if failed:
        print("RESULT: VERIFICATION FAILED")
        sys.exit(1)
    else:
        print("RESULT: VERIFICATION PASSED")
        sys.exit(0)

if __name__ == '__main__':
    main()
