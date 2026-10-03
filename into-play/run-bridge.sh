#!/usr/bin/env bash
# Start the bridge, drive a whole game through it from a WebSocket client, and
# fail if the game does not actually advance.
#
# This is the test that matters for the bridge, and it cannot be a unit test:
# the whole question is whether three threads — engine, socket, client — hand
# control to each other correctly, and nothing short of a running game asks it.
#
#   ./into-play/run-bridge.sh            build if needed, then run
#   SKIP_BUILD=1 ./into-play/run-bridge.sh
set -euo pipefail

cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
HERE="into-play"
OUT="${OUT:-/tmp/into-play-spike}"
PORT="${PORT:-8099}"
SECONDS_BUDGET="${SECONDS_BUDGET:-45}"
NODE="${NODE:-node}"

if [[ "${SKIP_BUILD:-}" != "1" || ! -f "$OUT/CP.txt" ]]; then
  "$HERE/run-spike.sh" --build-only >/dev/null 2>&1 || true
fi

if [[ ! -f "$OUT/CP.txt" ]]; then
  echo "no classpath at $OUT/CP.txt — run $HERE/run-spike.sh first" >&2
  exit 1
fi

CP="$OUT:$(cat "$OUT/CP.txt")"

echo "==> starting bridge on port $PORT"
java -Xmx2g -cp "$CP" forge.intoplay.BridgeMain forge-gui/res "$PORT" > /tmp/bridge-server.log 2>&1 &
SERVER=$!
# Killed on every exit path, including the error ones: a stranded JVM holding
# the port makes the next run fail for the wrong reason.
trap 'kill $SERVER 2>/dev/null || true' EXIT

# The card pool is ~34k scripts and takes a few seconds; wait for the line that
# says the socket is up rather than guessing at a sleep.
for _ in $(seq 1 60); do
  grep -q "bridge listening" /tmp/bridge-server.log && break
  sleep 1
done
if ! grep -q "bridge listening" /tmp/bridge-server.log; then
  echo "bridge never came up:" >&2
  grep -v "not assigned to any set" /tmp/bridge-server.log | tail -20 >&2
  exit 1
fi

echo "==> driving a game"
set +e
"$NODE" "$HERE/bridge-client.mjs" "ws://localhost:$PORT/play" "$SECONDS_BUDGET"
RC=$?
set -e

if [[ $RC -ne 0 ]]; then
  echo "--- server log ---" >&2
  grep -v "not assigned to any set" /tmp/bridge-server.log | tail -30 >&2
fi
exit $RC
