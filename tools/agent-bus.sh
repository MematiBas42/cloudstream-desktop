#!/usr/bin/env bash
# ==============================================================================
# CloudStream Multi-Agent High-Precision Signal & Contract Bus
# Industrial Standard: Zero-Chatter Typed Signals & Contract Verification
# ==============================================================================

set -euo pipefail

BUS_DIR="/tmp/cloudstream_agent_bus"
mkdir -p "$BUS_DIR"
CONTRACTS_FILE="$BUS_DIR/contracts.jsonl"
SIGNALS_FILE="$BUS_DIR/signals.jsonl"

CMD="${1:-}"

case "$CMD" in
    publish-contract)
        # Usage: publish-contract <DOMAIN> <FILE> <API_SIGNATURE> <TEST_STATUS>
        DOMAIN="${2:?Missing DOMAIN}"
        FILE="${3:?Missing FILE}"
        API="${4:?Missing API_SIGNATURE}"
        STATUS="${5:-VERIFIED}"
        TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

        ENTRY=$(cat <<EOF
{"timestamp":"$TIMESTAMP","domain":"$DOMAIN","file":"$FILE","api":"$API","status":"$STATUS"}
EOF
)
        # Atomic append via flock
        (
            flock -x 201
            echo "$ENTRY" >> "$CONTRACTS_FILE"
        ) 201>"$BUS_DIR/.contracts.lock"

        echo "[agent-bus] Contract published by $DOMAIN: $FILE -> $API ($STATUS)"
        ;;

    list-contracts)
        if [[ -f "$CONTRACTS_FILE" ]]; then
            cat "$CONTRACTS_FILE"
        else
            echo "[]"
        fi
        ;;

    notify)
        # Usage: notify <FROM> <TO> <SIGNAL_TYPE> <DETAILS>
        FROM="${2:?Missing FROM}"
        TO="${3:?Missing TO}"
        SIG_TYPE="${4:?Missing SIGNAL_TYPE}"
        DETAILS="${5:?Missing DETAILS}"
        TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

        SIGNAL=$(cat <<EOF
{"timestamp":"$TIMESTAMP","from":"$FROM","to":"$TO","type":"$SIG_TYPE","details":"$DETAILS"}
EOF
)
        (
            flock -x 202
            echo "$SIGNAL" >> "$SIGNALS_FILE"
        ) 202>"$BUS_DIR/.signals.lock"

        echo "[agent-bus] [$SIG_TYPE] $FROM -> $TO: $DETAILS"
        ;;

    recent)
        if [[ -f "$SIGNALS_FILE" ]]; then
            tail -n 20 "$SIGNALS_FILE"
        else
            echo "No signals recorded."
        fi
        ;;

    clear)
        rm -f "$CONTRACTS_FILE" "$SIGNALS_FILE" "$BUS_DIR/state.json"
        echo "[agent-bus] Cleaned all agent bus state."
        ;;

    *)
        echo "Usage: $0 {publish-contract|list-contracts|notify|recent|clear}"
        exit 1
        ;;
esac
