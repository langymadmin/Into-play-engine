#!/usr/bin/env bash
# The one card, end to end: Bolt at something the engine cannot see.
#
# Starts the bridge, then drives a deliberate sequence — play a Mountain, cast
# Lightning Bolt, aim it off the table, pay for it, let it resolve — and fails
# unless the engine itself confirms each step. Where run-bridge.sh proves a game
# can run, this proves the fork's reason to exist works through the whole stack.
#
#   ./into-play/run-bolt.sh
set -euo pipefail

cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
HERE="into-play"
OUT="${OUT:-/tmp/into-play-spike}"
PORT="${PORT:-8099}"
# Two casts, and the second waits for a land to untap on a later turn.
SECONDS_BUDGET="${SECONDS_BUDGET:-70}"
NODE="${NODE:-node}"

if [[ "${SKIP_BUILD:-}" != "1" || ! -f "$OUT/CP.txt" ]]; then
  "$HERE/run-spike.sh" --build-only >/dev/null 2>&1 || true
fi
if [[ ! -f "$OUT/CP.txt" ]]; then
  echo "no classpath at $OUT/CP.txt — run $HERE/run-spike.sh first" >&2
  exit 1
fi

# target/classes ahead of the jars, so a locally recompiled Forge class wins.
CP="$OUT"
for m in forge-core forge-game forge-ai forge-gui; do CP="$CP:$m/target/classes"; done
CP="$CP:$(cat "$OUT/CP.txt")"

echo "==> starting bridge on port $PORT"
java -Xmx2g -cp "$CP" forge.intoplay.BridgeMain forge-gui/res "$PORT" > /tmp/bridge-server.log 2>&1 &
SERVER=$!
echo $SERVER > /tmp/bridge.pid
trap 'kill $SERVER 2>/dev/null || true; rm -f /tmp/bridge.pid' EXIT

for _ in $(seq 1 90); do
  grep -q "bridge listening" /tmp/bridge-server.log && break
  sleep 1
done
if ! grep -q "bridge listening" /tmp/bridge-server.log; then
  echo "bridge never came up:" >&2
  grep -v "not assigned to any set" /tmp/bridge-server.log | tail -20 >&2
  exit 1
fi

echo "==> casting"
set +e
"$NODE" "$HERE/bolt-client.mjs" "ws://localhost:$PORT/play" "$SECONDS_BUDGET"
RC=$?
set -e

if [[ $RC -ne 0 ]]; then
  echo "--- server log ---" >&2
  grep -v "not assigned to any set" /tmp/bridge-server.log | tail -30 >&2
fi
exit $RC
