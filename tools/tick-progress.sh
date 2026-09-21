#!/usr/bin/env bash
# ==============================================================================
# tools/tick-progress.sh
# Atomically tick a task row in docs/audit/REMEDIATION_PROGRESS.md
# Serialized via kernel-level POSIX flock so concurrent agents never collide.
#
# Usage: ./tools/tick-progress.sh <TASK_ID> <STATUS> "<note>"
#   TASK_ID  e.g. W1-02, W3-GATE
#   STATUS   DONE | PARTIAL | FAIL | RUNNING
#   note     short single-line evidence (line counts, parity %, test result)
# ==============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOARD="$REPO_ROOT/docs/audit/REMEDIATION_PROGRESS.md"
LOCK_FILE="/tmp/cloudstream_progress.lock"

TASK_ID="${1:-}"
STATUS="${2:-}"
NOTE="${3:-}"

if [[ -z "$TASK_ID" || -z "$STATUS" ]]; then
    echo "Usage: $0 <TASK_ID> <DONE|PARTIAL|FAIL|RUNNING> \"<note>\"" >&2
    exit 1
fi

if [[ ! -f "$BOARD" ]]; then
    echo "[ERROR] Progress board not found: $BOARD" >&2
    exit 2
fi

case "$STATUS" in
    DONE)    ICON="✅" ;;
    PARTIAL) ICON="⚠️" ;;
    FAIL)    ICON="❌" ;;
    RUNNING) ICON="🔄" ;;
    *) echo "[ERROR] Unknown status: $STATUS (use DONE|PARTIAL|FAIL|RUNNING)" >&2; exit 1 ;;
esac

exec 210>"$LOCK_FILE"
if ! flock -x -w 120 210; then
    echo "[ERROR] Timed out waiting 120s for progress board lock" >&2
    exit 1
fi

BOARD="$BOARD" TASK_ID="$TASK_ID" ICON="$ICON" NOTE="$NOTE" python3 - <<'PYEOF'
import os, re, sys

board = os.environ["BOARD"]
task_id = os.environ["TASK_ID"]
icon = os.environ["ICON"]
note = os.environ["NOTE"].replace("|", "/").replace("\n", " ").strip() or "—"

with open(board, "r", encoding="utf-8") as f:
    lines = f.read().split("\n")

# Match a task row: | W1-02 | ⬜ | ... |   (bold IDs like **W1-GATE** also supported)
row_re = re.compile(r'^\|\s*\**' + re.escape(task_id) + r'\**\s*\|')
found = False

for i, line in enumerate(lines):
    if not row_re.match(line):
        continue
    cells = line.split("|")
    # cells: ['', ' ID ', ' STATUS ', ' Task ', ' Target ', ' Note ', '']
    if len(cells) < 6:
        continue
    cells[2] = f" {icon} "
    cells[-2] = f" {note} "
    lines[i] = "|".join(cells)
    found = True
    break

if not found:
    sys.stderr.write(f"[ERROR] Task row not found in board: {task_id}\n")
    sys.exit(3)

text = "\n".join(lines)

# ---- Recompute the summary counter table from the live task rows ----
wave_titles = {
    "1": "Dalga 1 — Çekirdek Temel & Başlatma",
    "2": "Dalga 2 — Durum Makineleri, Servisler & Ağ",
    "3": "Dalga 3 — Aksiyonlar, Oynatıcı & Medya Akışı",
    "4": "Dalga 4 — Arama, Ayarlar, Yardımcılar & Sertleştirme",
}
totals = {w: 0 for w in wave_titles}
done = {w: 0 for w in wave_titles}

task_row_re = re.compile(r'^\|\s*\**W([1-4])-(\d{2})\**\s*\|\s*(\S+)\s*\|')
for line in text.split("\n"):
    m = task_row_re.match(line)
    if not m:
        continue  # GATE rows are excluded from the per-wave task counters
    wave, st = m.group(1), m.group(3)
    totals[wave] += 1
    if st == "✅":
        done[wave] += 1

def wave_icon(w):
    if totals[w] == 0:
        return "⬜"
    if done[w] == totals[w]:
        return "✅"
    if done[w] == 0:
        return "⬜"
    return "🔄"

summary_lines = ["| Dalga | Görev | Tamamlanan | Durum |", "|:---|:---:|:---:|:---|"]
for w in ("1", "2", "3", "4"):
    summary_lines.append(f"| {wave_titles[w]} | {totals[w]} | {done[w]} | {wave_icon(w)} |")

grand_total = sum(totals.values())
grand_done = sum(done.values())
grand_icon = "✅" if grand_done == grand_total and grand_total > 0 else ("🔄" if grand_done else "⬜")
summary_lines.append(f"| **TOPLAM** | **{grand_total}** | **{grand_done}** | {grand_icon} |")

summary_block = "\n".join(summary_lines)
text = re.sub(
    r'\| Dalga \| Görev \| Tamamlanan \| Durum \|\n\|:---\|:---:\|:---:\|:---\|\n(?:\|.*\|\n)+',
    summary_block + "\n",
    text,
    count=1,
)

tmp = board + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    f.write(text)
os.replace(tmp, board)

print(f"[tick-progress] {task_id} -> {icon}  ({grand_done}/{grand_total} complete)")
PYEOF

RC=$?
flock -u 210
exec 210>&-
exit $RC
