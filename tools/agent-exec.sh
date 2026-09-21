#!/usr/bin/env bash
# ==============================================================================
# CloudStream Multi-Agent Deterministic Resource Mutex & Execution Wrapper
# Industrial Standard: Zero Blind Polling, Kernel-Level POSIX flock Synchronization
# ==============================================================================

set -euo pipefail

LOCK_FILE="/tmp/cloudstream_build.lock"
BUS_DIR="/tmp/cloudstream_agent_bus"
mkdir -p "$BUS_DIR"
STATE_FILE="$BUS_DIR/state.json"

AGENT_ID="${CLAUDE_AGENT_ID:-$(basename "$(readlink /proc/$$/cwd 2>/dev/null || pwd)")_$$}"
ACTION="${1:-}"

if [[ -z "$ACTION" ]]; then
    echo "Usage: $0 {gradle|run} <command...>"
    exit 1
fi
shift

# Function to record state atomically
update_state() {
    local status="$1"
    local command="$2"
    local timestamp
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    local tmp_file
    tmp_file=$(mktemp "$BUS_DIR/state.XXXXXX")

    cat <<EOF > "$tmp_file"
{
  "active_agent": "$AGENT_ID",
  "status": "$status",
  "command": "$command",
  "timestamp": "$timestamp",
  "pid": $$
}
EOF
    mv -f "$tmp_file" "$STATE_FILE"
}

cleanup() {
    local exit_code=$?
    if [[ -f "$STATE_FILE" ]]; then
        local current_agent
        current_agent=$(grep '"active_agent"' "$STATE_FILE" 2>/dev/null | cut -d '"' -f 4 || true)
        if [[ "$current_agent" == "$AGENT_ID" ]]; then
            rm -f "$STATE_FILE"
        fi
    fi
    exit $exit_code
}

trap cleanup EXIT INT TERM

case "$ACTION" in
    gradle)
        # Execute Gradle with kernel-level non-starving FIFO lock (300s timeout)
        exec 200>"$LOCK_FILE"

        # Wait deterministically for the lock without polling ps aux
        if ! flock -x -w 300 200; then
            echo "[agent-exec] ERROR: Timed out waiting 300s for build lock on $LOCK_FILE" >&2
            exit 1
        fi

        update_state "COMPILING" "./gradlew $*"
        echo "[agent-exec] Lock acquired by $AGENT_ID. Executing: ./gradlew $*"

        set +e
        ./gradlew "$@"
        EXIT_CODE=$?
        set -e

        flock -u 200
        exec 200>&-

        echo "[agent-exec] Lock released by $AGENT_ID (exit code $EXIT_CODE)."
        exit $EXIT_CODE
        ;;

    run)
        # General locked execution
        exec 200>"$LOCK_FILE"
        if ! flock -x -w 300 200; then
            echo "[agent-exec] ERROR: Timed out waiting 300s for lock" >&2
            exit 1
        fi
        update_state "RUNNING" "$*"
        set +e
        "$@"
        EXIT_CODE=$?
        set -e
        flock -u 200
        exec 200>&-
        exit $EXIT_CODE
        ;;

    status)
        if [[ -f "$STATE_FILE" ]]; then
            cat "$STATE_FILE"
        else
            echo '{"status": "IDLE", "active_agent": null}'
        fi
        exit 0
        ;;

    *)
        echo "Unknown action: $ACTION. Use 'gradle', 'run' or 'status'." >&2
        exit 1
        ;;
esac
